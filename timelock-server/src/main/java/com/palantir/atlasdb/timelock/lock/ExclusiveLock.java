/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.timelock.lock;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Queues;
import com.palantir.atlasdb.timelock.util.LoggablePreconditions;
import com.palantir.logsafe.SafeArg;

import net.jcip.annotations.GuardedBy;

public class ExclusiveLock implements AsyncLock {

    @GuardedBy("this")
    private final Queue<LockRequest> queue = Queues.newArrayDeque();
    @GuardedBy("this")
    private UUID currentHolder = null;

    @Override
    public synchronized CompletableFuture<Void> lock(UUID requestId) {
        return submit(new LockRequest(requestId, false));
    }

    @Override
    public CompletableFuture<Void> waitUntilAvailable(UUID requestId) {
        return submit(new LockRequest(requestId, true));
    }

    private CompletableFuture<Void> submit(LockRequest request) {
        queue.add(request);
        processQueue();

        return request.result;
    }

    @Override
    public synchronized void unlock(UUID requestId) {
        checkIsCurrentHolder(requestId);

        currentHolder = null;
        processQueue();
    }

    @VisibleForTesting
    synchronized UUID getCurrentHolder() {
        return currentHolder;
    }

    private void processQueue() {
        while (!queue.isEmpty() && currentHolder == null) {
            LockRequest head = queue.poll();
            if (!head.releaseImmediately) {
                currentHolder = head.requestId;
            }

            completeRequest(head);
        }
    }

    private void completeRequest(LockRequest request) {
        boolean wasCompleted = request.result.complete(null);
        LoggablePreconditions.checkState(
                wasCompleted,
                "Request was already completed when it was granted the lock",
                SafeArg.of("requestId", request.requestId));
    }

    private void checkIsCurrentHolder(UUID requestId) {
        LoggablePreconditions.checkState(
                requestId.equals(currentHolder),
                "ExclusiveLock may not be unlocked by a non-holder",
                SafeArg.of("currentHolder", currentHolder),
                SafeArg.of("request", requestId));
    }


    private class LockRequest {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final UUID requestId;
        private final boolean releaseImmediately;

        LockRequest(UUID requestId, boolean releaseImmediately) {
            this.requestId = requestId;
            this.releaseImmediately = releaseImmediately;
        }
    }

}
