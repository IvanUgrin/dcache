package org.dcache.pinmanager;

import java.util.Collection;
import javax.security.auth.Subject;
import diskCacheV111.util.PnfsId;
import org.dcache.pinmanager.model.Pin;

public interface PinDao
{
    Pin storePin(Pin pin);

    Pin getPin(long id);
    Pin getPin(PnfsId pnfsId, long id);
    Pin getPin(PnfsId pnfsId, String requestId);
    Pin getPin(long id, String sticky, Pin.State state);

    void deletePins(String[] pnfsIds);
    void deletePin(Pin pin);

    Collection<Pin> getPins();
    Collection<Pin> getPins(PnfsId pnfsId);
    Collection<Pin> getPins(Pin.State state);
    Collection<Pin> getPins(PnfsId pnfsId, String pool);

    void expirePins();

    boolean hasSharedSticky(Pin pin);
}