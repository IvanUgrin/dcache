/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.v4.*;
import org.dcache.xdr.*;
import java.io.IOException;

public class SET_SSV4res implements XdrAble {
    public int ssr_status;
    public SET_SSV4resok ssr_resok4;

    public SET_SSV4res() {
    }

    public SET_SSV4res(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeInt(ssr_status);
        switch ( ssr_status ) {
        case nfsstat.NFS_OK:
            ssr_resok4.xdrEncode(xdr);
            break;
        default:
            break;
        }
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        ssr_status = xdr.xdrDecodeInt();
        switch ( ssr_status ) {
        case nfsstat.NFS_OK:
            ssr_resok4 = new SET_SSV4resok(xdr);
            break;
        default:
            break;
        }
    }

}
// End of SET_SSV4res.java