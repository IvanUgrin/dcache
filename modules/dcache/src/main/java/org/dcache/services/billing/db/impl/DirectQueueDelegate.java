/*
COPYRIGHT STATUS:
Dec 1st 2001, Fermi National Accelerator Laboratory (FNAL) documents and
software are sponsored by the U.S. Department of Energy under Contract No.
DE-AC02-76CH03000. Therefore, the U.S. Government retains a  world-wide
non-exclusive, royalty-free license to publish or reproduce these documents
and software for U.S. Government purposes.  All documents and software
available from this server are protected under the U.S. and Foreign
Copyright Laws, and FNAL reserves all rights.

Distribution of the software available from this server is free of
charge subject to the user following the terms of the Fermitools
Software Legal Information.

Redistribution and/or modification of the software shall be accompanied
by the Fermitools Software Legal Information  (including the copyright
notice).

The user is asked to feed back problems, benefits, and/or suggestions
about the software to the Fermilab Software Providers.

Neither the name of Fermilab, the  URA, nor the names of the contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

DISCLAIMER OF LIABILITY (BSD):

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED  WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED  WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL FERMILAB,
OR THE URA, OR THE U.S. DEPARTMENT of ENERGY, OR CONTRIBUTORS BE LIABLE
FOR  ANY  DIRECT, INDIRECT,  INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
OF SUBSTITUTE  GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY  OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT  OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE  POSSIBILITY OF SUCH DAMAGE.

Liabilities of the Government:

This software is provided by URA, independent from its Prime Contract
with the U.S. Department of Energy. URA is acting independently from
the Government and in its own private capacity and is not acting on
behalf of the U.S. Government, nor as its contractor nor its agent.
Correspondingly, it is understood and agreed that the U.S. Government
has no connection to this software and in no manner whatsoever shall
be liable for nor assume any responsibility or obligation for any claim,
cost, or damages arising out of or resulting from the use of the software
available from this server.

Export Control:

All documents and software available from this server are subject to U.S.
export control laws.  Anyone downloading information from this server is
obligated to secure any necessary Government licenses before exporting
documents or software obtained from this server.
 */
package org.dcache.services.billing.db.impl;

import org.dcache.services.billing.db.data.DoorRequestData;
import org.dcache.services.billing.db.data.MoverData;
import org.dcache.services.billing.db.data.PoolHitData;
import org.dcache.services.billing.db.data.StorageData;
import org.dcache.services.billing.histograms.data.IHistogramData;

/**
 * Provides direct insertion into the queues without any special handling.
 *
 * @author arossi
 */
public class DirectQueueDelegate extends QueueDelegate {

    @Override
    protected void handlePut(DoorRequestData data) {
        if (!dropMessagesAtLimit) {
            try {
                doorQueue.put(data);
            } catch (InterruptedException t) {
                processInterrupted(data);
            }
        } else if (!doorQueue.offer(data)) {
            processDroppedData(data);
        }
    }

    @Override
    protected void handlePut(MoverData data) {
        if (!dropMessagesAtLimit) {
            try {
                moverQueue.put(data);
            } catch (InterruptedException t) {
                processInterrupted(data);
            }
        } else if (!moverQueue.offer(data)) {
            processDroppedData(data);
        }
    }

    @Override
    protected void handlePut(PoolHitData data) {
        if (!dropMessagesAtLimit) {
            try {
                hitQueue.put(data);
            } catch (InterruptedException t) {
                processInterrupted(data);
            }
        } else if (!hitQueue.offer(data)) {
            processDroppedData(data);
        }
    }

    @Override
    protected void handlePut(StorageData data) {
        if (!dropMessagesAtLimit) {
            try {
                storageQueue.put(data);
            } catch (InterruptedException t) {
                processInterrupted(data);
            }
        } else if (!storageQueue.offer(data)) {
            processDroppedData(data);
        }
    }

    @Override
    protected void initializeInternal() {
    }

    private void processInterrupted(IHistogramData data) {
        dropped.incrementAndGet();
        logger.warn("queueing of data was interrupted; "
                        + "{} entries have been dropped", dropped.get());
        logger.debug("failed to store {}", data);
    }

    private void processDroppedData(IHistogramData data) {
        dropped.incrementAndGet();
        logger.info("encountered max queue limit; "
                        + "{} entries have been dropped", dropped.get());
        logger.debug("queue limit prevented storage of {}", data);
    }
}
