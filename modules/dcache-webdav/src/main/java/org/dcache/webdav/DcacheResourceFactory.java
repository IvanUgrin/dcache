package org.dcache.webdav;

import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;
import io.milton.http.HttpManager;
import io.milton.http.Request;
import io.milton.http.ResourceFactory;
import io.milton.resource.Resource;
import io.milton.servlet.ServletRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import diskCacheV111.poolManager.PoolMonitorV5;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.FileLocality;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.FsPath;
import diskCacheV111.util.PermissionDeniedCacheException;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.util.PnfsId;
import diskCacheV111.util.TimeoutCacheException;
import diskCacheV111.vehicles.DoorRequestInfoMessage;
import diskCacheV111.vehicles.DoorTransferFinishedMessage;
import diskCacheV111.vehicles.HttpDoorUrlInfoMessage;
import diskCacheV111.vehicles.HttpProtocolInfo;
import diskCacheV111.vehicles.IoDoorEntry;
import diskCacheV111.vehicles.IoDoorInfo;
import diskCacheV111.vehicles.PnfsCreateEntryMessage;
import diskCacheV111.vehicles.PoolIoFileMessage;
import diskCacheV111.vehicles.PoolMoverKillMessage;
import diskCacheV111.vehicles.ProtocolInfo;

import dmg.cells.nucleus.AbstractCellComponent;
import dmg.cells.nucleus.CellCommandListener;
import dmg.cells.nucleus.CellMessageReceiver;
import dmg.cells.nucleus.CellPath;
import dmg.cells.services.login.LoginManagerChildrenInfo;

import org.dcache.auth.SubjectWrapper;
import org.dcache.auth.Subjects;
import org.dcache.auth.attributes.Restriction;
import org.dcache.cells.CellStub;
import org.dcache.missingfiles.AlwaysFailMissingFileStrategy;
import org.dcache.missingfiles.MissingFileStrategy;
import org.dcache.namespace.FileAttribute;
import org.dcache.poolmanager.PoolMonitor;
import org.dcache.util.Args;
import org.dcache.util.PingMoversTask;
import org.dcache.util.RedirectedTransfer;
import org.dcache.util.Slf4jSTErrorListener;
import org.dcache.util.Transfer;
import org.dcache.util.TransferRetryPolicies;
import org.dcache.util.TransferRetryPolicy;
import org.dcache.util.list.DirectoryEntry;
import org.dcache.util.list.DirectoryListPrinter;
import org.dcache.util.list.ListDirectoryHandler;
import org.dcache.vehicles.FileAttributes;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.dcache.namespace.FileAttribute.*;
import static org.dcache.namespace.FileType.*;

/**
 * This ResourceFactory exposes the dCache name space through the
 * Milton WebDAV framework.
 */
public class DcacheResourceFactory
    extends AbstractCellComponent
    implements ResourceFactory, CellMessageReceiver, CellCommandListener
{
    private static final Logger _log =
        LoggerFactory.getLogger(DcacheResourceFactory.class);

    public static final String TRANSACTION_ATTRIBUTE = "org.dcache.transaction";

    private static final Set<FileAttribute> REQUIRED_ATTRIBUTES =
        EnumSet.of(TYPE, PNFSID, CREATION_TIME, MODIFICATION_TIME, SIZE,
                   MODE, OWNER, OWNER_GROUP);

    private static final String HTML_TEMPLATE_NAME = "page";

    // Additional attributes needed for PROPFIND requests; e.g., to supply
    // values for properties.
    private static final Set<FileAttribute> PROPFIND_ATTRIBUTES = Sets.union(
            EnumSet.of(CHECKSUM, ACCESS_LATENCY, RETENTION_POLICY),
            PoolMonitorV5.getRequiredAttributesForFileLocality());

    private static final String PROTOCOL_INFO_NAME = "Http";
    private static final int PROTOCOL_INFO_MAJOR_VERSION = 1;
    private static final int PROTOCOL_INFO_MINOR_VERSION = 1;
    private static final int PROTOCOL_INFO_UNKNOWN_PORT = 0;

    private static final long PING_DELAY = 300000;

    private static final Splitter PATH_SPLITTER =
        Splitter.on('/').omitEmptyStrings();

    /**
     * In progress transfers. The key of the map is the session
     * id of the transfer.
     *
     * Note that the session id is cast to an integer - this is
     * because HttpProtocolInfo uses integer ids. Casting the
     * session ID increases the risk of collision due to wrapping
     * of the ID. However this can only happen if transfers are
     * longer than 50 days.
     */
    private final Map<Integer,HttpTransfer> _transfers =
        Maps.newConcurrentMap();

    private ListDirectoryHandler _list;

    private ScheduledExecutorService _executor;

    private int _moverTimeout = 180000;
    private TimeUnit _moverTimeoutUnit = MILLISECONDS;
    private long _killTimeout = 1500;
    private TimeUnit _killTimeoutUnit = MILLISECONDS;
    private long _transferConfirmationTimeout = 60000;
    private TimeUnit _transferConfirmationTimeoutUnit = MILLISECONDS;
    private int _bufferSize = 65536;
    private CellStub _poolStub;
    private CellStub _poolManagerStub;
    private CellStub _billingStub;
    private PnfsHandler _pnfs;
    private String _ioQueue;
    private PathMapper _pathMapper;
    private List<FsPath> _allowedPaths =
        Collections.singletonList(FsPath.ROOT);
    private InetAddress _internalAddress;
    private String _path;
    private boolean _doRedirectOnRead = true;
    private boolean _doRedirectOnWrite = true;
    private boolean _isOverwriteAllowed;
    private boolean _isAnonymousListingAllowed;

    private String _staticContentPath;
    private STGroup _listingGroup;
    private ImmutableMap<String,String> _templateConfig;

    private TransferRetryPolicy _retryPolicy =
        TransferRetryPolicies.tryOncePolicy(_moverTimeout, _moverTimeoutUnit);

    private MissingFileStrategy _missingFileStrategy =
        new AlwaysFailMissingFileStrategy();

    private PoolMonitor _poolMonitor;

    public DcacheResourceFactory()
        throws UnknownHostException
    {
        _internalAddress = InetAddress.getLocalHost();
    }

    @Required
    public void setPoolMonitor(PoolMonitor monitor)
    {
        _poolMonitor = monitor;
    }

    /**
     * Returns the kill timeout in milliseconds.
     */
    public long getKillTimeout()
    {
        return _killTimeout;
    }

    /**
     * The kill timeout is the time we wait for a transfer to
     * terminate after we killed the mover.
     *
     * @param timeout The mover timeout in milliseconds
     */
    public void setKillTimeout(long timeout)
    {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        _killTimeout = timeout;
    }

    public void setKillTimeoutUnit(TimeUnit unit)
    {
        _killTimeoutUnit = checkNotNull(unit);
    }

    public TimeUnit getKillTimeoutUnit()
    {
        return _killTimeoutUnit;
    }

    /**
     * Returns the mover timeout in milliseconds.
     */
    public int getMoverTimeout()
    {
        return _moverTimeout;
    }

    /**
     * The mover timeout is the time we wait for the mover to start
     * after having been enqueued.
     *
     * @param timeout The mover timeout in milliseconds
     */
    public void setMoverTimeout(int timeout)
    {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        _moverTimeout = timeout;
        _retryPolicy = TransferRetryPolicies.tryOncePolicy(_moverTimeout, _moverTimeoutUnit);
    }

    public void setMoverTimeoutUnit(TimeUnit unit)
    {
        _moverTimeoutUnit = checkNotNull(unit);
        _retryPolicy = TransferRetryPolicies.tryOncePolicy(_moverTimeout, _moverTimeoutUnit);
    }

    public TimeUnit getMoverTimeoutUnit()
    {
        return _moverTimeoutUnit;
    }

    /**
     * Returns the transfer confirmation timeout in milliseconds.
     */
    public long getTransferConfirmationTimeout()
    {
        return _transferConfirmationTimeout;
    }

    /**
     * The transfer confirmation timeout is the time we wait after we
     * know that an upload has finished and until we received the
     * transfer confirmation message from the pool.
     *
     * @param timeout The transfer confirmation timeout in milliseconds
     */
    public void setTransferConfirmationTimeout(long timeout)
    {
        _transferConfirmationTimeout = timeout;
    }

    public void setTransferConfirmationTimeoutUnit(TimeUnit unit)
    {
        _transferConfirmationTimeoutUnit = checkNotNull(unit);
    }

    public TimeUnit getTransferConfirmationTimeoutUnit()
    {
        return _transferConfirmationTimeoutUnit;
    }

    /**
     * Returns the buffer size in bytes.
     */
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /**
     * Sets the size of the buffer used when proxying uploads.
     *
     * @param bufferSize The buffer size in bytes
     */
    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
    }

    /**
     * Provide the mapping between request path and dCache internal path.
     */
    @Required
    public void setPathMapper(PathMapper mapper)
    {
        _pathMapper = mapper;
    }

    /**
     * Set the list of paths for which we allow access. Paths are
     * separated by a colon. This paths are relative to the root path.
     */
    public void setAllowedPaths(String s)
    {
        List<FsPath> list = new ArrayList<>();
        for (String path: s.split(":")) {
            list.add(FsPath.create(path));
        }
        _allowedPaths = list;
    }

    /**
     * Returns the list of allowed paths.
     */
    public String getAllowedPaths()
    {
        StringBuilder s = new StringBuilder();
        for (FsPath path: _allowedPaths) {
            if (s.length() > 0) {
                s.append(':');
            }
            s.append(path);
        }
        return s.toString();
    }

    /**
     * Return the pool IO queue to use for WebDAV transfers.
     */
    public String getIoQueue()
    {
        return (_ioQueue == null) ? "" : _ioQueue;
    }

    /**
     * Sets the pool IO queue to use for WebDAV transfers.
     */
    public void setIoQueue(String ioQueue)
    {
        _ioQueue = (ioQueue != null && !ioQueue.isEmpty()) ? ioQueue : null;
    }

    /**
     * Sets whether read requests are redirected to the pool. If not,
     * then the door will act as a proxy.
     */
    public void setRedirectOnReadEnabled(boolean redirect)
    {
        _doRedirectOnRead = redirect;
    }

    public boolean isRedirectOnReadEnabled()
    {
        return _doRedirectOnRead;
    }

    public void setRedirectOnWriteEnabled(boolean redirect)
    {
        _doRedirectOnWrite = redirect;
    }

    public boolean isRedirectOnWriteEnabled()
    {
        return _doRedirectOnWrite;
    }

    /**
     * Sets whether existing files may be overwritten.
     */
    public void setOverwriteAllowed(boolean allowed)
    {
        _isOverwriteAllowed = allowed;
    }

    public boolean isOverwriteAllowed()
    {
        return _isOverwriteAllowed;
    }

    public void setAnonymousListing(boolean isAllowed)
    {
        _isAnonymousListingAllowed = isAllowed;
    }

    public boolean isAnonymousListing()
    {
        return _isAnonymousListingAllowed;
    }

    /**
     * Sets the cell stub for PnfsManager communication.
     */
    public void setPnfsStub(CellStub stub)
    {
        _pnfs = new PnfsHandler(stub);
    }

    /**
     * Sets the cell stub for pool communication.
     */
    public void setPoolStub(CellStub stub)
    {
        _poolStub = stub;
    }

    /**
     * Sets the cell stub for PoolManager communication.
     */
    public void setPoolManagerStub(CellStub stub)
    {
        _poolManagerStub = stub;
    }

    /**
     * Sets the cell stub for billing communication.
     */
    public void setBillingStub(CellStub stub)
    {
        _billingStub = stub;
    }

    /**
     * Sets the behaviour of this door when the user requests a file
     * that doesn't exist.
     */
    public void setMissingFileStrategy(MissingFileStrategy strategy)
    {
        _missingFileStrategy = strategy;
    }

    /**
     * Sets the ListDirectoryHandler used for directory listing.
     */
    public void setListHandler(ListDirectoryHandler list)
    {
        _list = list;
    }

    /**
     * Sets the resource containing the StringTemplateGroup for
     * directory listing.
     */
    public void setTemplateResource(org.springframework.core.io.Resource resource)
        throws IOException
    {
        _listingGroup = new STGroupFile(resource.getURL(), "UTF-8", '$', '$');
        _listingGroup.setListener(new Slf4jSTErrorListener(_log));

        /* StringTemplate has lazy initialisation, but this is very racey and
         * can break StringTemplate altogether:
         *
         *     https://github.com/antlr/stringtemplate4/issues/61
         *
         * here we force initialisation to work-around this.
         */
        _listingGroup.getInstanceOf(HTML_TEMPLATE_NAME);

    }

    @Required
    public void setTemplateConfig(ImmutableMap<String,String> config)
    {
        _templateConfig = config;
    }

    /**
     * Returns the static content path.
     */
    public String getStaticContentPath()
    {
        return _staticContentPath;
    }

    /**
     * The static content path is the path under which the service
     * exports the static content. This typically contains stylesheets
     * and image files.
     */
    public void setStaticContentPath(String path)
    {
        _staticContentPath = path;
    }

    /**
     * Sets the ScheduledExecutorService used for periodic tasks.
     */
    public void setExecutor(ScheduledExecutorService executor)
    {
        _executor = executor;
        _executor.scheduleAtFixedRate(new PingMoversTask<>(_transfers.values()),
                                      PING_DELAY, PING_DELAY,
                                      MILLISECONDS);
    }

    public void setInternalAddress(String ipString)
            throws IllegalArgumentException, UnknownHostException
    {
        if (!Strings.isNullOrEmpty(ipString)) {
            InetAddress address = InetAddresses.forString(ipString);
            if (address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("Wildcard address is not a valid local address: " + address);
            }
            _internalAddress = address;
        } else {
            _internalAddress = InetAddress.getLocalHost();
        }
    }

    public String getInternalAddress()
    {
        return _internalAddress.getHostAddress();
    }

    @Override
    public void getInfo(PrintWriter pw)
    {
        pw.println("Allowed paths: " + getAllowedPaths());
        pw.println("IO queue     : " + getIoQueue());
    }

    @Override
    public Resource getResource(String host, String requestPath)
    {
        if (_log.isDebugEnabled()) {
            _log.debug("Resolving " + HttpManager.request().getAbsoluteUrl());
        }

        FsPath dCachePath = _pathMapper.asDcachePath(ServletRequest.getRequest(),
                requestPath);
        return getResource(dCachePath);
    }

    /**
     * Returns the resource object for a path.
     *
     * @param path The full path
     */
    public DcacheResource getResource(FsPath path)
    {
        if (!isAllowedPath(path)) {
            return null;
        }

        String requestPath = getRequestPath();
        boolean haveRetried = false;
        Subject subject = getSubject();

        try {
            while(true) {
                try {
                    PnfsHandler pnfs = new PnfsHandler(_pnfs, subject, getRestriction());
                    Set<FileAttribute> requestedAttributes =
                            buildRequestedAttributes();
                    FileAttributes attributes =
                        pnfs.getFileAttributes(path.toString(), requestedAttributes);
                    return getResource(path, attributes);
                } catch (FileNotFoundCacheException e) {
                    if(haveRetried) {
                        return null;
                    } else {
                        switch(_missingFileStrategy.recommendedAction(subject,
                                path, requestPath)) {
                        case FAIL:
                            return null;
                        case RETRY:
                            haveRetried = true;
                            break;
                        }
                    }
                }
            }
        } catch (PermissionDeniedCacheException e) {
            throw new UnauthorizedException(e.getMessage(), e, null);
        } catch (CacheException e) {
            throw new WebDavException(e.getMessage(), e, null);
        }
    }

    /**
     * Returns the resource object for a path.
     *
     * @param path The full path
     * @param attributes The attributes of the object identified by the path
     */
    private DcacheResource getResource(FsPath path, FileAttributes attributes)
    {
        if (attributes.getFileType() == DIR) {
            return new DcacheDirectoryResource(this, path, attributes);
        } else {
            return new DcacheFileResource(this, path, attributes);
        }
    }

    /**
     * Returns a boolean indicating if the request should be redirected to a
     * pool.
     *
     * @param request a Request
     * @return a boolean indicating if the request should be redirected
     */
    public boolean shouldRedirect(Request request)
    {
        switch (request.getMethod()) {
        case GET:
            return isRedirectOnReadEnabled();
        case PUT:
            boolean expects100Continue =
                    Objects.equal(request.getExpectHeader(), HttpHeaders.Values.CONTINUE);
            return isRedirectOnWriteEnabled() && expects100Continue;
        default:
            return false;
        }
    }

    /**
     * Creates a new file. The door will relay all data to the pool.
     */
    public DcacheResource createFile(FsPath path, InputStream inputStream, Long length)
            throws CacheException, InterruptedException, IOException,
                   URISyntaxException
    {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();

        WriteTransfer transfer = new WriteTransfer(_pnfs, subject, restriction, path);
        _transfers.put((int) transfer.getId(), transfer);
        try {
            boolean success = false;
            transfer.setProxyTransfer(true);
            transfer.createNameSpaceEntry();
            try {
                transfer.setLength(length);
                try {
                    transfer.selectPoolAndStartMover(_ioQueue, _retryPolicy);
                    String uri = transfer.waitForRedirect(_moverTimeout, _moverTimeoutUnit);
                    if (uri == null) {
                        throw new TimeoutCacheException("Server is busy (internal timeout)");
                    }
                    transfer.relayData(inputStream);
                } finally {
                    transfer.killMover(_killTimeout, _killTimeoutUnit);
                }
                success = true;
            } finally {
                if (!success) {
                    transfer.deleteNameSpaceEntry();
                }
            }
        } catch (CacheException e) {
            transfer.notifyBilling(e.getRc(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   "Transfer interrupted");
            throw e;
        } catch (IOException | RuntimeException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   e.toString());
            throw e;
        } finally {
            _transfers.remove((int) transfer.getId());
        }

        return getResource(path);
    }

    public String getWriteUrl(FsPath path, Long length)
            throws CacheException, InterruptedException,
                   URISyntaxException
    {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();

        String uri = null;
        WriteTransfer transfer = new WriteTransfer(_pnfs, subject, restriction, path);
        _transfers.put((int) transfer.getId(), transfer);
        try {
            transfer.createNameSpaceEntry();
            try {
                transfer.setLength(length);
                transfer.selectPoolAndStartMover(_ioQueue, _retryPolicy);
                uri = transfer.waitForRedirect(_moverTimeout, _moverTimeoutUnit);
                if (uri == null) {
                    throw new TimeoutCacheException("Server is busy (internal timeout)");
                }

                transfer.setStatus("Mover " + transfer.getPool() + "/" +
                        transfer.getMoverId() + ": Waiting for completion");
            } finally {
                if (uri == null) {
                    transfer.killMover(_killTimeout, _killTimeoutUnit);
                    transfer.deleteNameSpaceEntry();
                }
            }
        } catch (CacheException e) {
            transfer.notifyBilling(e.getRc(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                    "Transfer interrupted");
            throw e;
        } catch (RuntimeException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                    e.toString());
            throw e;
        } finally {
            if (uri == null) {
                _transfers.remove((int) transfer.getId());
            }
        }
        return uri;
    }


    /**
     * Reads the content of a file. The door will relay all data from
     * a pool.
     */
    public void readFile(FsPath path, PnfsId pnfsid,
                         OutputStream outputStream, io.milton.http.Range range)
            throws CacheException, InterruptedException, IOException,
                   URISyntaxException
    {
        ReadTransfer transfer = beginRead(path, pnfsid, true, null);
        try {
            transfer.relayData(outputStream, range);
        } catch (CacheException e) {
            transfer.notifyBilling(e.getRc(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   "Transfer interrupted");
            throw e;
        } catch (IOException | RuntimeException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   e.toString());
            throw e;
        } finally {
            transfer.killMover(_killTimeout, _killTimeoutUnit);
            _transfers.remove((int) transfer.getId());
        }
    }

    /**
     * Performs a directory listing returning a list of Resource
     * objects.
     */
    public List<DcacheResource> list(final FsPath path)
        throws InterruptedException, CacheException
    {
        if (!_isAnonymousListingAllowed && Subjects.isNobody(getSubject())) {
            throw new PermissionDeniedCacheException("Access denied");
        }

        final List<DcacheResource> result = new ArrayList<>();
        DirectoryListPrinter printer =
            new DirectoryListPrinter()
            {
                @Override
                public Set<FileAttribute> getRequiredAttributes()
                {
                    return buildRequestedAttributes();
                }

                @Override
                public void print(FsPath dir, FileAttributes dirAttr, DirectoryEntry entry)
                {
                    result.add(getResource(path.child(entry.getName()),
                                           entry.getFileAttributes()));
                }
            };

        _list.printDirectory(getSubject(), getRestriction(), printer, path, null,
                             Range.<Integer>all());
        return result;
    }

    private class FileLocalityWrapper
    {
        private final FileLocality _inner;

        FileLocalityWrapper(FileLocality inner)
        {
            _inner = inner;
        }

        public boolean isOnline()
        {
            return _inner == FileLocality.ONLINE;
        }

        public boolean isNearline()
        {
            return _inner == FileLocality.NEARLINE;
        }

        public boolean isOnlineAndNearline()
        {
            return _inner == FileLocality.ONLINE_AND_NEARLINE;
        }

        public boolean isLost()
        {
            return _inner == FileLocality.LOST;
        }

        public boolean isUnavailable()
        {
            return _inner == FileLocality.UNAVAILABLE;
        }
    }

    private String getRequestPath()
    {
        Request request = HttpManager.request();
        return URI.create(request.getAbsoluteUrl()).getPath();
    }

    private String getRemoteAddr()
    {
        return HttpManager.request().getRemoteAddr();
    }

    /**
     * Performs a directory listing, writing an HTML view to an output
     * stream.
     */
    public void list(FsPath path, Writer out)
        throws InterruptedException, CacheException, IOException
    {
        if (!_isAnonymousListingAllowed && Subjects.isNobody(getSubject())) {
            throw new PermissionDeniedCacheException("Access denied");
        }

        String requestPath = getRequestPath();
        String[] base =
            Iterables.toArray(PATH_SPLITTER.split(requestPath), String.class);
        final ST t = _listingGroup.getInstanceOf(HTML_TEMPLATE_NAME);

        if (t == null) {
            _log.error("template '{}' not found in templategroup: {}",
                    HTML_TEMPLATE_NAME, _listingGroup.getFileName());
            out.append(DcacheResponseHandler.templateNotFoundErrorPage(_listingGroup,
                    HTML_TEMPLATE_NAME));
            out.flush();
            return;
        }

        t.add("path", asList(UrlPathWrapper.forPaths(base)));
        t.add("static", _staticContentPath);
        t.add("subject", new SubjectWrapper(getSubject()));
        t.add("base", UrlPathWrapper.forEmptyPath());
        t.add("config", _templateConfig);

        DirectoryListPrinter printer =
                new DirectoryListPrinter() {
                    @Override
                    public Set<FileAttribute> getRequiredAttributes() {
                        return EnumSet.copyOf(Sets.union(PoolMonitorV5.getRequiredAttributesForFileLocality(),
                                EnumSet.of(MODIFICATION_TIME, TYPE, SIZE)));
                    }

                    @Override
                    public void print(FsPath dir, FileAttributes dirAttr, DirectoryEntry entry) {
                        FileAttributes attr = entry.getFileAttributes();
                        Date mtime = new Date(attr.getModificationTime());
                        UrlPathWrapper name =
                                UrlPathWrapper.forPath(entry.getName());
                        /* FIXME: SIZE is defined if client specifies the
                         * file's size before uploading.
                         */
                        boolean isUploading = !attr.isDefined(SIZE);
                        FileLocality locality = _poolMonitor.getFileLocality(attr, getRemoteAddr());
                        t.addAggr("files.{name,isDirectory,mtime,size,isUploading,locality}",
                                  name,
                                  attr.getFileType() == DIR,
                                  mtime,
                                  attr.getSizeIfPresent().transform(SizeWrapper::new).orNull(),
                                  isUploading,
                                  new FileLocalityWrapper(locality));
                    }
                };
        _list.printDirectory(getSubject(), getRestriction(), printer, path, null,
                             Range.<Integer>all());

        t.write(new AutoIndentWriter(out));
        out.flush();
    }

    /**
     * Deletes a file.
     */
    public void deleteFile(FileAttributes attributes, FsPath path)
        throws CacheException
    {
        PnfsHandler pnfs = new PnfsHandler(_pnfs, getSubject(), getRestriction());
        pnfs.deletePnfsEntry(attributes.getPnfsId(), path.toString(), EnumSet.of(REGULAR, LINK));
        sendRemoveInfoToBilling(attributes, path);
    }

    private void sendRemoveInfoToBilling(FileAttributes attributes, FsPath path)
    {
        DoorRequestInfoMessage infoRemove =
            new DoorRequestInfoMessage(getCellAddress().toString(), "remove");
        Subject subject = getSubject();
        infoRemove.setSubject(subject);
        infoRemove.setBillingPath(path.toString());
        infoRemove.setPnfsId(attributes.getPnfsId());
        infoRemove.setFileSize(attributes.getSizeIfPresent().or(0L));
        infoRemove.setClient(Subjects.getOrigin(subject).getAddress().getHostAddress());
        _billingStub.notify(infoRemove);
    }

    /**
     * Deletes a directory.
     */
    public void deleteDirectory(PnfsId pnfsid, FsPath path)
        throws CacheException
    {
        PnfsHandler pnfs = new PnfsHandler(_pnfs, getSubject(), getRestriction());
        pnfs.deletePnfsEntry(pnfsid, path.toString(),
                             EnumSet.of(DIR));
    }

    /**
     * Create a new directory.
     */
    public DcacheDirectoryResource
        makeDirectory(FileAttributes parent, FsPath path)
        throws CacheException
    {
        PnfsHandler pnfs = new PnfsHandler(_pnfs, getSubject(), getRestriction());
        PnfsCreateEntryMessage reply =
            pnfs.createPnfsDirectory(path.toString());
        FileAttributes attributes =
            pnfs.getFileAttributes(reply.getPnfsId(), REQUIRED_ATTRIBUTES);

        return new DcacheDirectoryResource(this, path, attributes);
    }

    public void move(FsPath sourcePath, PnfsId pnfsId, FsPath newPath)
        throws CacheException
    {
        PnfsHandler pnfs = new PnfsHandler(_pnfs, getSubject(), getRestriction());
        pnfs.renameEntry(pnfsId, sourcePath.toString(), newPath.toString(), true);
    }


    /**
     * Returns a read URL for a file.
     *
     * @param path The full path of the file.
     * @param pnfsid The PNFS ID of the file.
     */
    public String getReadUrl(FsPath path, PnfsId pnfsid,
            HttpProtocolInfo.Disposition disposition)
            throws CacheException, InterruptedException, URISyntaxException
    {
        return beginRead(path, pnfsid, false, disposition).getRedirect();
    }

    /**
     * Initiates a read operation.
     *
     *
     * @param path The full path of the file.
     * @param pnfsid The PNFS ID of the file.
     * @param isProxyTransfer
     * @return ReadTransfer encapsulating the read operation
     */
    private ReadTransfer beginRead(FsPath path, PnfsId pnfsid, boolean isProxyTransfer,
            HttpProtocolInfo.Disposition disposition) throws CacheException,
            InterruptedException, URISyntaxException
    {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();

        String uri = null;
        ReadTransfer transfer = new ReadTransfer(_pnfs, subject, restriction,
                path, pnfsid, disposition);
        transfer.setIsChecksumNeeded(isDigestRequested());
        _transfers.put((int) transfer.getId(), transfer);
        try {
            transfer.setProxyTransfer(isProxyTransfer);
            transfer.readNameSpaceEntry(false);
            try {
                transfer.selectPoolAndStartMover(_ioQueue, _retryPolicy);
                uri = transfer.waitForRedirect(_moverTimeout, _moverTimeoutUnit);
                if (uri == null) {
                    throw new TimeoutCacheException("Server is busy (internal timeout)");
                }
            } finally {
                transfer.setStatus(null);
            }
            transfer.setStatus("Mover " + transfer.getPool() + "/" +
                               transfer.getMoverId() + ": Waiting for completion");
        } catch (CacheException e) {
            transfer.notifyBilling(e.getRc(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   "Transfer interrupted");
            throw e;
        } catch (RuntimeException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   e.toString());
            throw e;
        } finally {
            if (uri == null) {
                transfer.killMover(_killTimeout, _killTimeoutUnit);
                _transfers.remove((int) transfer.getId());
            }
        }
        return transfer;
    }

    /**
     * Message handler for redirect messages from the pools.
     */
    public void messageArrived(HttpDoorUrlInfoMessage message)
    {
        HttpTransfer transfer = _transfers.get((int) message.getId());
        if (transfer != null) {
            transfer.redirect(message.getUrl());
        }
    }

    /**
     * Message handler for transfer completion messages from the
     * pools.
     */
    public void messageArrived(DoorTransferFinishedMessage message)
    {
        Transfer transfer = _transfers.get((int) message.getId());
        if (transfer != null) {
            transfer.finished(message);
        }
    }

    /**
     * Fall back message handler for mover creation replies. We
     * only receive these if the Transfer timed out before the
     * mover was created. Instead we kill the mover.
     */
    public void messageArrived(PoolIoFileMessage message)
    {
        if (message.getReturnCode() == 0) {
            String pool = message.getPoolName();
            _poolStub.notify(new CellPath(pool), new PoolMoverKillMessage(pool, message.getMoverId()));
        }
    }

    /**
     * Returns true if access to path is allowed through the WebDAV
     * door, false otherwise.
     */
    private boolean isAllowedPath(FsPath path)
    {
        for (FsPath allowedPath: _allowedPaths) {
            if (path.hasPrefix(allowedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the current Subject of the calling thread.
     */
    private static Subject getSubject()
    {
        return Subject.getSubject(AccessController.getContext());
    }

    private Restriction getRestriction()
    {
        HttpServletRequest servletRequest = ServletRequest.getRequest();
        return (Restriction) servletRequest.getAttribute(AuthenticationHandler.DCACHE_RESTRICTION_ATTRIBUTE);
    }

    /**
     * Returns the location URI of the current request. This is the
     * full request URI excluding user information, query and fragments.
     */
    private static URI getLocation() throws URISyntaxException
    {
        URI uri = new URI(HttpManager.request().getAbsoluteUrl());
        return new URI(uri.getScheme(), null, uri.getHost(),
                uri.getPort(), uri.getPath(), null, null);
    }

    /**
     * To emulate LoginManager we list ourselves as our child.
     */
    public static final String hh_get_children = "[-binary]";
    public Object ac_get_children(Args args)
    {
        boolean binary = args.hasOption("binary");
        if (binary) {
            String [] list = new String[] { getCellName() };
            return new LoginManagerChildrenInfo(getCellName(), getCellDomainName(), list);
        } else {
            return getCellName();
        }
    }

    /**
     * Provides information about the door and current transfers.
     */
    public static final String hh_get_door_info = "[-binary]";
    public Object ac_get_door_info(Args args)
    {
        List<IoDoorEntry> transfers = new ArrayList<>();
        for (Transfer transfer: _transfers.values()) {
            transfers.add(transfer.getIoDoorEntry());
        }

        IoDoorInfo doorInfo = new IoDoorInfo(getCellName(), getCellDomainName());
        doorInfo.setProtocol("HTTP", "1.1");
        doorInfo.setOwner("");
        doorInfo.setProcess("");
        doorInfo.setIoDoorEntries(transfers
                .toArray(new IoDoorEntry[transfers.size()]));
        return args.hasOption("binary") ? doorInfo : doorInfo.toString();
    }

    private void initializeTransfer(HttpTransfer transfer, Subject subject)
            throws URISyntaxException
    {
        transfer.setLocation(getLocation());
        transfer.setCellName(getCellName());
        transfer.setDomainName(getCellDomainName());
        transfer.setPoolManagerStub(_poolManagerStub);
        transfer.setPoolStub(_poolStub);
        transfer.setBillingStub(_billingStub);
        List<InetSocketAddress> addresses = Subjects.getOrigin(subject).getClientChain().stream().
                map(a -> new InetSocketAddress(a, PROTOCOL_INFO_UNKNOWN_PORT)).
                collect(Collectors.toList());
        transfer.setClientAddresses(addresses);
        transfer.setOverwriteAllowed(_isOverwriteAllowed);
    }

    private Set<FileAttribute> buildRequestedAttributes()
    {
        Set<FileAttribute> attributes = EnumSet.copyOf(REQUIRED_ATTRIBUTES);

        if (isDigestRequested()) {
            attributes.add(CHECKSUM);
        }

        if (isPropfindRequest()) {
            // FIXME: Unfortunately, Milton parses the request body after
            // requesting the Resource, so we cannot know which additional
            // attributes are being requested; therefore, we must request all
            // of them.
            attributes.addAll(PROPFIND_ATTRIBUTES);
        }

        return attributes;
    }

    private static boolean isDigestRequested()
    {
        switch (HttpManager.request().getMethod()) {
        case HEAD:
        case GET:
            // TODO: parse the Want-Digest to see if the requested digest(s) are
            //       supported.  If not then we can omit fetching the checksum
            //       values.
            return HttpManager.request().getHeaders().containsKey("Want-Digest");
        default:
            return false;
        }
    }


    private boolean isPropfindRequest()
    {
        return HttpManager.request().getMethod() == Request.Method.PROPFIND;
    }

    FileLocality calculateLocality(FileAttributes attributes, String clientIP)
    {
        return _poolMonitor.getFileLocality(attributes, clientIP);
    }

    /**
     * Specialisation of the Transfer class for HTTP transfers.
     */
    private class HttpTransfer extends RedirectedTransfer<String>
    {
        private URI _location;
        private InetSocketAddress _clientAddressForPool;
        protected HttpProtocolInfo.Disposition _disposition;

        public HttpTransfer(PnfsHandler pnfs, Subject subject,
                Restriction restriction, FsPath path) throws URISyntaxException
        {
            super(pnfs, subject, restriction, path);
            initializeTransfer(this, subject);
            _clientAddressForPool = getClientAddress();

            ServletRequest.getRequest().setAttribute(TRANSACTION_ATTRIBUTE,
                    getTransaction());
        }

        protected ProtocolInfo createProtocolInfo(InetSocketAddress address)
        {
            HttpProtocolInfo protocolInfo =
                new HttpProtocolInfo(
                        PROTOCOL_INFO_NAME,
                        PROTOCOL_INFO_MAJOR_VERSION,
                        PROTOCOL_INFO_MINOR_VERSION,
                        address,
                        getCellName(), getCellDomainName(),
                        _path.toString(),
                        _location,
                        _disposition);
            protocolInfo.setSessionId((int) getId());
            return protocolInfo;
        }

        @Override
        protected ProtocolInfo getProtocolInfoForPoolManager()
        {
            return createProtocolInfo(getClientAddress());
        }

        @Override
        protected ProtocolInfo getProtocolInfoForPool()
        {
            return createProtocolInfo(_clientAddressForPool);
        }

        public void setLocation(URI location)
        {
            _location = location;
        }

        public void setProxyTransfer(boolean isProxyTransfer)
        {
            if (isProxyTransfer) {
                _clientAddressForPool = new InetSocketAddress(_internalAddress, 0);
            } else {
                _clientAddressForPool = getClientAddress();
            }
        }
    }

    /**
     * Specialised HttpTransfer for downloads.
     */
    private class ReadTransfer extends HttpTransfer
    {
        public ReadTransfer(PnfsHandler pnfs, Subject subject,
                            Restriction restriction, FsPath path, PnfsId pnfsid,
                            HttpProtocolInfo.Disposition disposition)
                throws URISyntaxException
        {
            super(pnfs, subject, restriction, path);
            setPnfsId(pnfsid);
            _disposition = disposition;
        }

        public void setIsChecksumNeeded(boolean isChecksumNeeded)
        {
            if(isChecksumNeeded) {
                setAdditionalAttributes(Collections.singleton(CHECKSUM));
            } else {
                setAdditionalAttributes(Collections.<FileAttribute>emptySet());
            }
        }

        public void relayData(OutputStream outputStream, io.milton.http.Range range)
            throws IOException, CacheException, InterruptedException
        {
            setStatus("Mover " + getPool() + "/" + getMoverId() +
                      ": Opening data connection");
            try {
                URL url = new URL(getRedirect());
                HttpURLConnection connection =
                    (HttpURLConnection) url.openConnection();
                try {
                    connection.setRequestProperty("Connection", "Close");
                    if (range != null) {
                        connection.addRequestProperty("Range", String.format("bytes=%d-%d", range.getStart(), range.getFinish()));
                    }

                    connection.connect();
                    try (InputStream inputStream = connection
                            .getInputStream()) {
                        setStatus("Mover " + getPool() + "/" + getMoverId() +
                                ": Sending data");
                        ByteStreams.copy(inputStream, outputStream);
                        outputStream.flush();
                    }

                } finally {
                    connection.disconnect();
                }

                if (!waitForMover(_transferConfirmationTimeout, _transferConfirmationTimeoutUnit)) {
                    throw new CacheException("Missing transfer confirmation from pool");
                }
            } catch (SocketTimeoutException e) {
                throw new TimeoutCacheException("Server is busy (internal timeout)");
            } finally {
                setStatus(null);
            }
        }


        @Override
        public synchronized void finished(CacheException error)
        {
            super.finished(error);

            _transfers.remove((int) getId());

            if (error == null) {
                notifyBilling(0, "");
            } else {
                notifyBilling(error.getRc(), error.getMessage());
            }
        }
    }

    /**
     * Specialised HttpTransfer for uploads.
     */
    private class WriteTransfer extends HttpTransfer
    {
        public WriteTransfer(PnfsHandler pnfs, Subject subject,
                Restriction restriction, FsPath path) throws URISyntaxException
        {
            super(pnfs, subject, restriction, path);
        }

        public void relayData(InputStream inputStream)
                throws IOException, CacheException, InterruptedException
        {
            setStatus("Mover " + getPool() + "/" + getMoverId() +
                    ": Opening data connection");
            try {
                URL url = new URL(getRedirect());
                HttpURLConnection connection =
                        (HttpURLConnection) url.openConnection();
                try {
                    connection.setRequestMethod("PUT");
                    connection.setRequestProperty("Connection", "Close");
                    connection.setDoOutput(true);
                    if (getFileAttributes().isDefined(SIZE)) {
                        connection.setFixedLengthStreamingMode(getFileAttributes().getSize());
                    } else {
                        connection.setChunkedStreamingMode(8192);
                    }
                    connection.connect();
                    try (OutputStream outputStream = connection.getOutputStream()) {
                        setStatus("Mover " + getPool() + "/" + getMoverId() +
                                ": Receiving data");
                        ByteStreams.copy(inputStream, outputStream);
                        outputStream.flush();
                    }
                    if (connection.getResponseCode() != HttpResponseStatus.CREATED.code()) {
                        throw new CacheException(connection.getResponseMessage());
                    }
                } finally {
                    connection.disconnect();
                }

                if (!waitForMover(_transferConfirmationTimeout, _transferConfirmationTimeoutUnit)) {
                    throw new CacheException("Missing transfer confirmation from pool");
                }
            } catch (SocketTimeoutException e) {
                throw new TimeoutCacheException("Server is busy (internal timeout)");
            } finally {
                setStatus(null);
            }
        }

        /**
         * Sets the length of the file to be uploaded. The length is
         * optional and will be ignored if null.
         */
        public void setLength(Long length)
        {
            if (length != null) {
                super.setLength(length);
            }
        }

        @Override
        public synchronized void finished(CacheException error)
        {
            super.finished(error);

            _transfers.remove((int) getId());

            if (error == null) {
                notifyBilling(0, "");
            } else {
                notifyBilling(error.getRc(), error.getMessage());
            }
        }
    }
}
