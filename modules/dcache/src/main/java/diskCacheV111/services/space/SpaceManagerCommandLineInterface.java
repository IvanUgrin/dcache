package diskCacheV111.services.space;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import diskCacheV111.util.AccessLatency;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.util.PnfsId;
import diskCacheV111.util.RetentionPolicy;
import diskCacheV111.util.VOInfo;

import dmg.cells.nucleus.CellCommandListener;
import dmg.util.command.Argument;
import dmg.util.command.Command;
import dmg.util.command.DelayedCommand;
import dmg.util.command.Option;

import org.dcache.auth.FQAN;
import org.dcache.util.ColumnWriter;
import org.dcache.util.Glob;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.primitives.Longs.tryParse;

public class SpaceManagerCommandLineInterface implements CellCommandListener
{
    private SpaceManagerDatabase db;
    private PnfsHandler pnfs;
    private AccessLatency defaultAccessLatency;
    private RetentionPolicy defaultRetentionPolicy;
    private LinkGroupLoader linkGroupLoader;
    private Executor executor;

    @Required
    public void setExecutor(Executor executor)
    {
        this.executor = executor;
    }

    @Required
    public void setDatabase(SpaceManagerDatabase db)
    {
        this.db = db;
    }

    @Required
    public void setPnfs(PnfsHandler pnfs)
    {
        this.pnfs = pnfs;
    }

    @Required
    public void setDefaultAccessLatency(AccessLatency defaultAccessLatency)
    {
        this.defaultAccessLatency = defaultAccessLatency;
    }

    @Required
    public void setDefaultRetentionPolicy(RetentionPolicy defaultRetentionPolicy)
    {
        this.defaultRetentionPolicy = defaultRetentionPolicy;
    }

    @Required
    public void setLinkGroupLoader(LinkGroupLoader linkGroupLoader)
    {
        this.linkGroupLoader = linkGroupLoader;
    }

    @Transactional(rollbackFor = { Exception.class })
    private <T extends Serializable> T callInTransaction(Callable<T> callable) throws Exception
    {
        return callable.call();
    }

    /**
     * Base class for asynchronous commands.
     *
     * Executes all commands in a database transaction.
     */
    private abstract class AsyncCommand<T extends Serializable> extends DelayedCommand<T>
    {
        public AsyncCommand()
        {
            super(executor);
        }

        @Override
        protected final T execute() throws Exception
        {
            return callInTransaction(new Callable<T>()
            {
                @Override
                public T call() throws Exception
                {
                    return executeInTransaction();
                }
            });
        }

        protected abstract T executeInTransaction() throws Exception;
    }

    @Command(name = "release space", hint = "release reservation",
             usage = "Releases a space reservation. The files in the reservation are not deleted " +
                     "from dCache, but the space occupied by those files is no longer accounted " +
                     "for by the space manager. Such files will continue to appear as used space in " +
                     "their link group.")
    public class ReleaseSpaceCommand extends AsyncCommand<String>
    {
        @Argument
        long token;

        @Override
        public String executeInTransaction() throws DataAccessException
        {
            Space space = db.updateSpace(token,
                                         null,
                                         null,
                                         null,
                                         null,
                                         null,
                                         null,
                                         null,
                                         null,
                                         SpaceState.RELEASED);
            return space.toString();
        }
    }

    @Command(name = "update space", hint = "modify space reservation parameters")
    public class UpdateSpaceCommand extends AsyncCommand<String>
    {
        @Option(name = "size",
                usage = "Size in bytes, with optional byte unit suffix using either SI or IEEE 1541 prefixes.",
                metaVar="bytes")
        String size;

        @Option(name = "owner", usage = "User name or FQAN.", valueSpec="USER|FQAN")
        String owner;

        @Option(name = "lifetime",
                usage = "Lifetime in seconds since creation time.")
        Long lifetime;

        @Option(name = "eternal",
                usage = "Space reservation will never expire.")
        boolean eternal;

        @Option(name = "desc",
                usage = "Space token description.")
        String description;

        @Argument(metaVar="spacetoken", help = "Token of space reservation to update.")
        Long token;

        @Override
        public String executeInTransaction() throws DataAccessException, SpaceReleasedException, SpaceExpiredException
        {
            String group = null;
            String role = null;

            if (owner != null) {
                // check that linkgroup allows this owner combination
                Space space = db.getSpace(token);
                LinkGroup lg = db.getLinkGroup(space.getLinkGroupId());

                FQAN fqan = new FQAN(owner);
                group = fqan.getGroup();
                role = emptyToNull(fqan.getRole());

                boolean foundMatch = false;
                for (VOInfo info : lg.getVOs()) {
                    if (info.match(group, role)) {
                        foundMatch = true;
                        break;
                    }
                }
                if (!foundMatch) {
                    return "Cannot change owner to " + owner + ". " +
                            "Authorized for this link group are:\n"+
                            Joiner.on('\n').join(lg.getVOs());
                }
            }

            if (eternal) {
                if (lifetime != null) {
                    throw new IllegalArgumentException("Eternal reservations cannot have a lifetime.");
                }
                lifetime = -1L;
            }

            Space space = db.selectSpaceForUpdate(token);
            if (space.getState() == SpaceState.RELEASED) {
                throw new SpaceReleasedException("Space reservation has been released and cannot be updated.");
            }
            if (space.getState() == SpaceState.EXPIRED) {
                throw new SpaceExpiredException("Space reservation has expired and cannot be updated.");
            }
            space = db.updateSpace(space,
                                   group,
                                   role,
                                   null,
                                   null,
                                   null,
                                   (size != null ? Unit.parseByteQuantity(size) : null),
                                   lifetime == null ? null : lifetime == -1 ? -1 : lifetime * 1000,
                                   description,
                                   null);
            return space.toString();
        }
    }

    @Command(name = "ls link groups", hint = "list link groups",
             usage = "If an argument is given, the command displays all link groups with a name " +
                     "matching the pattern. If no argument is given, all link groups are " +
                     "displayed. The list can be further restricted using the options.\n\n" +

                     "For each link group the following information is displayed left to right: " +
                     "File types allowed in this link group (output(o), replica(r), custodial(c), " +
                     "nearline (n), online(o)), number of reservations in link group, reserved " +
                     "space, unreserved space, last refresh timestamp, and the link group name.\n\n" +

                     "Link groups are periodically imported from pool manager. The last refresh time " +
                     "indicates when the information was last updated.\n\n" +

                     "Link groups don't have a size. Only the current amount of free space in online " +
                     "pools accessible by the link group is known. Part of that free space is reserved " +
                     "(but not used) by space reservations. This is reported as reserved space. The " +
                     "remaining free space is reported as unreserved space. Unreserved space can be " +
                     "reserved by creating new space reservations or by enlarging existing reservations. " +
                     "Since pools may go offline, unreserved space can become negative. In this case " +
                     "the link group is overallocated and the reserved space is no longer guaranteed.")
    public class ListLinkGroupsCommand extends AsyncCommand<String>
    {
        @Option(name = "a", usage = "Include link groups that have not been refreshed recently.")
        boolean all;

        @Option(name = "l", usage = "Include additional details.")
        boolean verbose;

        @Option(name = "al", usage = "Only show link groups that allow this access latency.",
                values = { "online", "nearline" })
        AccessLatency al;

        @Option(name = "rp", usage = "Only show link groups that allow this retention policy.",
                values = { "output", "replica", "custodial"})
        RetentionPolicy rp;

        @Option(name = "h",
                usage = "Use unit suffixes Byte, Kilobyte, Megabyte, Gigabyte, Terabyte and " +
                        "Petabyte in order to reduce the number of digits to three or less " +
                        "using base 10 for sizes.")
        boolean humanReadable;

        @Argument(required = false)
        Glob name;

        @Override
        public String executeInTransaction() throws DataAccessException
        {
            SpaceManagerDatabase.LinkGroupCriterion criterion = db.linkGroupCriterion();
            if (!all) {
                criterion.whereUpdateTimeAfter(linkGroupLoader.getLatestUpdateTime());
            }
            if (al != null) {
                criterion.allowsAccessLatency(al);
            }
            if (rp != null) {
                criterion.allowsRetentionPolicy(rp);
            }
            if (name != null) {
                criterion.whereNameMatches(name);
            }

            ColumnWriter writer = new ColumnWriter()
                    .abbreviateBytes(humanReadable)
                    .header("FLAGS").fixed("-").left("output").left("replica").left("custodial").fixed(":").left("nearline").left("online")
                    .space().header("CNT").right("spaces")
                    .space().header("RESVD").bytes("reserved")
                    .fixed(" + ")
                    .header("AVAIL").bytes("available")
                    .fixed(" = ")
                    .header("FREE").bytes("free")
                    .space().header("UPDATED").date("updated")
                    .space().header("NAME").left("name");

            for (LinkGroup group : db.getLinkGroups(criterion)) {
                writer.row()
                        .value("output", group.isOutputAllowed() ? 'o' : '-')
                        .value("replica", group.isReplicaAllowed() ? 'r' : '-')
                        .value("custodial", group.isCustodialAllowed() ? 'c' : '-')
                        .value("nearline", group.isNearlineAllowed() ? 'n' : '-')
                        .value("online", group.isOnlineAllowed() ? 'o' : '-')
                        .value("spaces", db.getCountOfSpaces(db.spaceCriterion().whereLinkGroupIs(group.getId())))
                        .value("reserved", group.getReservedSpaceInBytes())
                        .value("available", group.getAvailableSpaceInBytes())
                        .value("free", group.getFreeSpace())
                        .value("updated", group.getUpdateTime())
                        .value("name", group.getName());
                if (verbose) {
                    for (VOInfo voInfo : group.getVOs()) {
                        writer.row("    " + voInfo);
                    }
                }
            }
            return writer.toString();
        }
    }

    @Command(name = "ls spaces", hint = "list space reservations",
             usage = "If an argument is given, the command displays space reservations for which the " +
                     "space description matches the pattern. If the argument is an integer, the argument " +
                     "is interpreted as a space token and a matching space reservation is displayed." +
                     "If no argument is given, all space reservations are displayed. The list can be " +
                     "further restricted using the options.\n\n" +

                     "For each space reservation the following information may be displayed left to right: " +
                     "Space token, reservation state (reserved(-), released(r), expired(e)), default " +
                     "retention policy, default access latency, number of files in space, owner, allocated " +
                     "bytes, used bytes, unused bytes, size of space, creation time, expiration time, and " +
                     "description.\n\n" +

                     "Space reservations have a size. This size can be partitioned into space that is " +
                     "used by files stored in the space reservation, space that is allocated for named " +
                     "files that have not yet been uploaded, and free space. The latter two make up the " +
                     "reserved space of the link group within which the space exists. Note that ones " +
                     "a file is uploaded to a space reservation, the space consumed by the file is " +
                     "obviously not free anymore and will thus not appear in the link group statistics.")
    public class ListSpacesCommand extends AsyncCommand<String>
    {
        @Option(name = "a", usage = "Include ephemeral, expired and released spaces.")
        boolean all;

        @Option(name = "l", usage = "Include additional details.")
        boolean verbose;

        @Option(name = "e", usage = "Include ephemeral spaces.")
        boolean ephemeral;

        @Option(name = "owner",
                usage = "Only show spaces whose owner matches this pattern.",
                valueSpec="USER|FQAN")
        String owner;

        @Option(name = "al",
                usage = "Only show spaces with this default access latency.",
                values = { "online", "nearline" })
        AccessLatency al;

        @Option(name = "rp",
                usage = "Only show spaces with this default retention policy.",
                values = { "replica", "custodial" })
        RetentionPolicy rp;

        @Option(name = "state",
                values = { "reserved", "released", "expired" },
                usage = "Only show spaces in one of these states.")
        SpaceState[] states;

        @Option(name = "lg",
                usage = "Only show spaces in the named link group.")
        String linkGroup;

        @Option(name = "h",
                usage = "Use unit suffixes Byte, Kilobyte, Megabyte, Gigabyte, Terabyte and " +
                        "Petabyte in order to reduce the number of digits to three or less " +
                        "using base 10 for sizes.")
        boolean humanReadable;

        @Option(name = "limit",
                usage = "Limit output to this many space reservations.",
                metaVar = "rows")
        Integer limit = 10000;

        @Argument(required = false,
                  help = "Only show spaces with this token or a description matching this pattern.",
                  valueSpec = "TOKEN|PATTERN")
        Glob pattern;

        @Override
        public String executeInTransaction() throws DataAccessException
        {
            ColumnWriter writer = new ColumnWriter()
                    .abbreviateBytes(humanReadable)
                    .header("TOKEN").right("token");
            if (all || verbose || states != null && states.length > 0 || pattern != null) {
                writer.space().header("S").left("status");
            }
            if (verbose || linkGroup == null) {
                writer.space().header("LINKGROUP").left("linkgroup");
            }
            writer
                    .space().header("RETENTION").left("rp")
                    .space().header("LATENCY").left("al")
                    .space().header("FILES").right("files");
            if (verbose || owner != null) {
                writer.space().header("OWNER").left("owner");
            }
            writer.space().header("ALLO").bytes("allocated")
                    .fixed(" + ").header("USED").bytes("used")
                    .fixed(" + ").header("FREE").bytes("free")
                    .fixed(" = ").header("SIZE").bytes("size");
            if (verbose) {
                writer.space().header("CREATED").date("created");
            }
            if (ephemeral || all || verbose || pattern != null) {
                writer.space().header("EXPIRES").date("expires");
            }
            writer.space().header("DESCRIPTION").left("description");

            Iterable<Space> spaces;
            if (pattern == null) {
                spaces = db.getSpaces(whereOptionsMatch(db.spaceCriterion()), limit);
            } else {
                spaces = db.getSpaces(whereOptionsMatch(db.spaceCriterion()).whereDescriptionMatches(pattern), limit);
                Long token = tryParse(pattern.toString());
                if (token != null) {
                    List<Space> moreSpaces =
                            db.getSpaces(whereOptionsMatch(db.spaceCriterion()).whereTokenIs(token), limit);
                    spaces = concat(moreSpaces, spaces);
                }
            }

            Map<Long,String> linkGroups =
                    Maps.transformValues(Maps.uniqueIndex(db.getLinkGroups(), LinkGroup.getId), LinkGroup.getName);

            for (Space space : spaces) {
                char status;
                if (space.getState() == SpaceState.EXPIRED) {
                    status = 'e';
                } else if (space.getState() == SpaceState.RELEASED) {
                    status = 'r';
                } else {
                    status = '-';
                }
                writer.row()
                        .value("token", space.getId())
                        .value("status", status)
                        .value("linkgroup", linkGroups.get(space.getLinkGroupId()))
                        .value("rp", space.getRetentionPolicy())
                        .value("al", space.getAccessLatency())
                        .value("files", db.getCountOfFiles(db.fileCriterion().whereSpaceTokenIs(space.getId())))
                        .value("allocated", space.getAllocatedSpaceInBytes())
                        .value("used", space.getUsedSizeInBytes())
                        .value("free", space.getAvailableSpaceInBytes())
                        .value("size", space.getSizeInBytes())
                        .value("created", space.getCreationTime())
                        .value("expires", (space.getLifetime() > -1) ? space.getCreationTime() + space.getLifetime() : null)
                        .value("description", space.getDescription())
                        .value("owner", toOwner(space.getVoGroup(), space.getVoRole()));
            }
            return writer.toString();
        }

        private SpaceManagerDatabase.SpaceCriterion whereOptionsMatch(SpaceManagerDatabase.SpaceCriterion criterion)
        {
            if (owner != null) {
                FQAN fqan = new FQAN(owner);
                criterion.whereGroupMatches(new Glob(fqan.getGroup()));
                if (fqan.hasRole()) {
                    criterion.whereRoleMatches(new Glob(fqan.getRole()));
                }
            }
            if (linkGroup != null) {
                criterion.whereLinkGroupIs(db.getLinkGroupByName(linkGroup).getId());
            }
            if (al != null) {
                criterion.whereAccessLatencyIs(al);
            }
            if (rp != null) {
                criterion.whereRetentionPolicyIs(rp);
            }
            if (states != null && states.length > 0) {
                criterion.whereStateIsIn(states);
            } else if (!all && pattern == null) {
                criterion.whereStateIsIn(SpaceState.RESERVED);
            }
            if (!ephemeral && !all && pattern == null) {
                criterion.whereLifetimeIs(-1);
            }
            return criterion;
        }
    }

    private String toOwner(String voGroup, String voRole)
    {
        if (voGroup.charAt(0) != '/' || voRole == null || voRole.equals("*")) {
            return voGroup;
        } else {
            return voGroup + "/Role=" + voRole;
        }
    }

    @Command(name = "ls files", hint = "list file reservations",
             usage = "If an argument is given, the command displays reserved files for which the " +
                     "PNFS ID matches the argument, or the path matches the pattern. If no argument is " +
                     "given, all space reservations are displayed. The list can be further restricted " +
                     "using the options.\n\n" +

                     "For each file reservation the following information may be displayed left to right: " +
                     "Whether the name space has been deleted (d), state, (allocated(a), transferring(t), " +
                     "stored(s), flushed(f)), space token, owner, size in bytes, creation time, expiration " +
                     "time, PNFS ID, and path.\n\n" +

                     "A space reservation can contain file reservations that consume the reserved space. " +
                     "Each file reservation is in one of four states: ALLOCATED, TRANSFERRING, STORED, " +
                     "or FLUSHED.\n\n" +

                     "ALLOCATED files have been preregistered to be stored in this space reservation. " +
                     "The file has however not yet been uploaded. Since such files have not yet been " +
                     "created, no PNFS ID is associated with them. They are only identified by file " +
                     "system path.\n\n" +

                     "TRANSFERRING files are in the process of being uploaded. Such files have " +
                     "a PNFS ID associated with them.\n\n" +

                     "STORED files have finished uploading.\n\n" +

                     "FLUSHED files have been flushed to tape and no longer consume space in the " +
                     "reservation.\n\n" +

                     "Files in the states ALLOCATED and TRANSFERRING have a limited lifetime. Unless " +
                     "moved to the STORED or FLUSHED state, such entries will be deleted when they " +
                     "expire. The associated name space entry of TRANSFERRING files will be deleted " +
                     "too.")
    public class ListFilesCommand extends AsyncCommand<String>
    {
        @Option(name = "owner",
                usage = "Only show files whose owner matches this pattern.",
                valueSpec="USER|FQAN")
        String owner;

        @Option(name = "token",
                usage = "Only show files in space reservation with this token.")
        Long token;

        @Option(name = "l",
                usage = "Include additional details.")
        boolean verbose;

        @Option(name = "a",
                usage = "Include deleted and flushed files.")
        boolean all;

        @Option(name = "p",
                usage = "Lookup file system path from PNFS ID. This may slow down listing " +
                        "considerably.")
        boolean lookup;

        @Option(name = "limit",
                usage = "Limit output to this many space reservations.",
                metaVar = "rows")
        Integer limit = 10000;

        @Option(name = "h",
                usage = "Use unit suffixes Byte, Kilobyte, Megabyte, Gigabyte, Terabyte and " +
                        "Petabyte in order to reduce the number of digits to three or less " +
                        "using base 10 for sizes.")
        boolean humanReadable;

        @Option(name = "state",
                values = { "reserved", "transferring", "stored", "flushed" },
                usage = "Only show files in one of these states.")
        FileState[] states;

        @Argument(required = false,
                  help = "Only show spaces with this PNFSID or a path matching this pattern.",
                  valueSpec = "PNFSID|PATH|PATTERN")
        Glob pattern;

        @Override
        public String executeInTransaction() throws DataAccessException, CacheException
        {
            ColumnWriter writer = new ColumnWriter()
                    .abbreviateBytes(humanReadable)
                    .header("FLAGS").left("deleted").left("state")
                    .space().header("SPACE").right("token")
                    .space().header("OWNER").left("owner")
                    .space().header("SIZE").bytes("size")
                    .space().header("CREATED").date("created");
            if (verbose) {
                writer.space().header("EXPIRES").date("expires");
            }
            writer.space().header("PNFSID").left("pnfsid")
                    .space().header("PATH").left("path");

            SpaceManagerDatabase.FileCriterion criterion = db.fileCriterion();
            whereOptionsMatch(criterion);
            Iterable<File> files;
            if (pattern != null) {
                try {
                    PnfsId pnfsId = pnfs.getPnfsIdByPath(pattern.toString());
                    files = db.getFiles(whereOptionsMatch(db.fileCriterion().wherePnfsIdIs(pnfsId)), limit);
                } catch (FileNotFoundCacheException ignored) {
                    files = db.getFiles(whereOptionsMatch(db.fileCriterion().wherePathMatches(pattern)), limit);
                }
                try {
                    PnfsId pnfsId = new PnfsId(pattern.toString());
                    List<File> moreFiles = db.getFiles(whereOptionsMatch(db.fileCriterion().wherePnfsIdIs(pnfsId)), limit);
                    files = concat(moreFiles, files);
                } catch (IllegalArgumentException ignored) {
                }
            } else {
                files = db.getFiles(whereOptionsMatch(db.fileCriterion()), limit);
            }

            for (File file : files) {
                char state;
                FileState fileState = file.getState();
                if (fileState == FileState.ALLOCATED) {
                    state = 'a';
                } else if (fileState == FileState.TRANSFERRING) {
                    state = 't';
                } else if (fileState == FileState.STORED) {
                    state = 's';
                } else if (fileState == FileState.FLUSHED) {
                    state = 'f';
                } else {
                    state = '-';
                }
                PnfsId pnfsId = file.getPnfsId();
                String path;
                try {
                    path = (pnfsId == null || !lookup || file.isDeleted()) ? file.getPnfsPath() : pnfs.getPathByPnfsId(pnfsId);
                } catch (FileNotFoundCacheException e) {
                    path = file.getPnfsPath();
                }
                writer.row()
                        .value("owner", toOwner(file.getVoGroup(), file.getVoRole()))
                        .value("created", file.getCreationTime())
                        .value("size", file.getSizeInBytes())
                        .value("pnfsid", pnfsId)
                        .value("path", path)
                        .value("token", file.getSpaceId())
                        .value("deleted", file.isDeleted() ? 'd' : '-')
                        .value("state", state)
                        .value("expires",
                               state == 'a' || state == 't' ? file.getCreationTime() + file.getLifetime() : null);
            }

            return writer.toString();
        }

        private SpaceManagerDatabase.FileCriterion whereOptionsMatch(SpaceManagerDatabase.FileCriterion criterion)
        {
            if (owner != null) {
                FQAN fqan = new FQAN(owner);
                criterion.whereGroupMatches(new Glob(fqan.getGroup()));
                if (fqan.hasRole()) {
                    criterion.whereRoleMatches(new Glob(fqan.getRole()));
                }
            }
            if (token != null) {
                criterion.whereSpaceTokenIs(token);
            }
            if (states != null && states.length > 0) {
                criterion.whereStateIsIn(states);
            } else if (!all && pattern == null) {
                criterion.whereStateIsIn(FileState.ALLOCATED, FileState.TRANSFERRING, FileState.STORED);
            }
            if (!all && pattern == null) {
                criterion.whereDeletedIs(false);
            }
            return criterion;
        }
    }

    @Command(name = "reserve space", hint = "create space reservation",
             usage = "A space reservation has a size, an access latency, a retention policy, " +
                     "and an owner. It may have a description, and a lifetime. If the lifetime " +
                     "is exceeded, the reservation expires and the files in it are released. " +
                     "The owner is only used to authorize creation of the reservation in the " +
                     "link group, and to authorize the release of the reservation - it is " +
                     "not used to authorize uploads to the reservation.\n\n" +

                     "Space reservations are created in link groups. The link group authorizes " +
                     "reservations. The owner of the reservation as well as its file type " +
                     "(retention policy and access latency) must be allowed in the link group " +
                     "within which the reservation is created.\n\n" +

                     "The size argument accepts an optional byte unit suffix using either SI or " +
                     "IEEE 1541 prefixes.")
    public class ReserveSpaceCommand extends AsyncCommand<String>
    {
        @Option(name = "owner", usage = "User name or FQAN.", valueSpec="USER|FQAN")
        String owner;

        @Option(name = "al", usage = "Access latency.",
                values = { "online", "nearline" })
        AccessLatency al = defaultAccessLatency;

        @Option(name = "rp", usage = "Retention policy.",
                values = { "replica", "custodial"})
        RetentionPolicy rp  = defaultRetentionPolicy;

        @Option(name = "desc")
        String description;

        @Option(name = "lg", required = true, metaVar = "name",
                usage = "Link group within which to create the space reservation.")
        String lg;

        @Option(name = "lifetime", metaVar = "seconds",
                usage = "Lifetime in seconds. If no lifetime is given, the reservation will " +
                        "never expire.")
        Long lifetime;

        @Argument
        String size;

        @Override
        public String executeInTransaction() throws DataAccessException
        {
            long sizeInBytes = Unit.parseByteQuantity(size);

            LinkGroup linkGroup = db.getLinkGroupByName(lg);
            if (linkGroup.getUpdateTime() < linkGroupLoader.getLatestUpdateTime()) {
                return "Link group " + lg + " has existed, but it is no longer published by pool manager.";
            }


            String group = null;
            String role = null;
            if (owner != null) {
                FQAN fqan = new FQAN(owner);
                group = fqan.getGroup();
                role = emptyToNull(fqan.getRole());
            }
            List<Long> linkGroups = db.findLinkGroupIds(sizeInBytes,
                                                        group,
                                                        role,
                                                        al,
                                                        rp,
                                                        linkGroupLoader.getLatestUpdateTime());

            if (!linkGroups.contains(linkGroup.getId())) {
                return "Link group " + lg + " cannot accommodate the reservation requested, \n"+
                        "check that the link group satisfies the following criteria: \n"+
                        "\t it can fit the size you are requesting ("+sizeInBytes+"),\n"+
                        "\t owner you specified (" + owner +") is authorized, and \n"+
                        "\t retention policy and access latency you specified (" + rp +  ',' + al + ") are allowed.";
            }

            Space space = db.insertSpace(group,
                                         role,
                                         rp,
                                         al,
                                         linkGroup.getId(),
                                         sizeInBytes,
                                         (lifetime == null || lifetime == -1) ? -1 : lifetime * 1000,
                                         description,
                                         SpaceState.RESERVED,
                                         0,
                                         0);
            return space.toString();
        }
    }

    @Command(name = "release file", hint = "remove file reservation",
             usage = "Removes a file reservation from its space reservation without deleting " +
                     "the file from dCache. The space in the reservation that was set aside " +
                     "for the file will be available to other files, assuming the link group " +
                     "has enough free space.\n\n" +

                     "This command is the file level equivalent to releasing the entire space " +
                     "reservation.")
    public class ReleaseFileCommand extends AsyncCommand<String>
    {
        @Option(name = "pnfsid", usage = "PNFS ID of file.")
        PnfsId pnfsId;

        @Option(name = "path",
                usage = "File system path. Only allowed for files for which no PNFS ID has been " +
                        "bound yet. Other file reservations must be removed by PNFS ID.")
        String path;

        @Override
        public String executeInTransaction() throws DataAccessException
        {
            if (path != null) {
                File f = db.getUnboundFile(path);
                db.removeFile(f.getId());
                return "Removed reservation for " + path + '.';
            }
            if (pnfsId != null) {
                File f = db.getFile(pnfsId);
                db.removeFile(f.getId());
                return "Removed reservation for " + pnfsId + '.';
            }
            return null;
        }
    }

    private enum Unit
    {
        K(1000L),
        KB(1000L),
        KIB(1024L),
        M(1000L * 1000),
        MB(1000L * 1000),
        MIB(1024L * 1024),
        G(1000L * 1000 * 1000),
        GB(1000L * 1000 * 1000),
        GIB(1024L * 1024 * 1024),
        T(1000L * 1000 * 1000 * 1000),
        TB(1000L * 1000 * 1000 * 1000),
        TIB(1024L * 1024 * 1024 * 1024),
        P(1000L * 1000 * 1000 * 1000 * 1000),
        PB(1000L * 1000 * 1000 * 1000 * 1000),
        PIB(1024L * 1024 * 1024 * 1024 * 1024);

        long factor;

        Unit(long factor)
        {
            this.factor = factor;
        }

        private static long parseByteQuantity(String s)
        {
            try {
                s = s.toUpperCase();
                for (Unit unit : Unit.values()) {
                    if (s.endsWith(unit.name())) {
                        String sSize = s.substring(0, s.length() - unit.name().length());
                        long size = (long) (Double.parseDouble(sSize) * unit.factor + 0.5);
                        return checkNonNegative(size);
                    }
                }
                return checkNonNegative(Long.parseLong(s));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot convert size specified (" + s + ") to non-negative number. \n"
                                                           + "Valid definitions of size:\n"
                                                           + "\t\t - a number of bytes (long integer less than 2^64) \n"
                                                           + "\t\t - 100k, 100kB, 100KB, 100KiB, 100M, 100MB, 100MiB, 100G, 100GB, \n"
                                                           + "\t\t   100GiB, 10T, 10.5TB, 100TiB, 2P, 2.3PB, 1PiB\n"
                                                           + "see http://en.wikipedia.org/wiki/Gigabyte for an explanation.");
            }
        }

        private static long checkNonNegative(long size)
        {
            if (size < 0L) {
                throw new IllegalArgumentException("Size must be non-negative.");
            }
            return size;
        }
    }
}