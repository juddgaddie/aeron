/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.SessionDecorator;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

import java.io.File;
import java.io.PrintStream;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.aeron.CommonContext.ENDPOINT_PARAM_NAME;

public class ConsensusModuleHarness implements AutoCloseable, ClusteredService
{
    private static final long MAX_CATALOG_ENTRIES = 1024;

    private final ClusteredMediaDriver clusteredMediaDriver;
    private final ClusteredServiceContainer clusteredServiceContainer;
    private final AtomicBoolean isTerminated = new AtomicBoolean();
    private final Aeron aeron;
    private final ClusteredService service;
    private final AtomicBoolean serviceOnStart = new AtomicBoolean();
    private final AtomicInteger serviceOnMessageCounter = new AtomicInteger(0);
    private final IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1);
    private final ClusterMember[] members;
    private final Subscription[] memberStatusSubscriptions;
    private final MemberStatusAdapter[] memberStatusAdapters;
    private final Publication[] memberStatusPublications;
    private final MemberStatusPublisher memberStatusPublisher = new MemberStatusPublisher();
    private final boolean cleanOnClose;
    private final File harnessDir;

    private ClusteredMediaDriver leaderCluster;
    private ClusteredServiceContainer leaderContainer;
    private File leaderHarnessDir;
    private int thisMemberIndex = -1;
    private int leaderIndex = -1;

    ConsensusModuleHarness(
        final ConsensusModule.Context context,
        final ClusteredService service,
        final MemberStatusListener[] memberStatusListeners,
        final boolean isCleanStart,
        final boolean cleanOnClose)
    {
        this.service = service;
        this.members = ClusterMember.parse(context.clusterMembers());
        this.leaderIndex = context.appointedLeaderId();
        this.thisMemberIndex = context.clusterMemberId();

        harnessDir = new File(IoUtil.tmpDirName(), "aeron-cluster-" + context.clusterMemberId());
        final String mediaDriverPath = new File(harnessDir, "driver").getPath();
        final File clusterDir = new File(harnessDir, "aeron-cluster");
        final File archiveDir = new File(harnessDir, "aeron-archive");
        final File serviceDir = new File(harnessDir, "clustered-service");

        clusteredMediaDriver = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(mediaDriverPath)
                .warnIfDirectoryExists(isCleanStart)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .errorHandler(Throwable::printStackTrace)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .aeronDirectoryName(mediaDriverPath)
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .archiveDir(archiveDir)
                .deleteArchiveOnStart(isCleanStart),
            context
                .aeronDirectoryName(mediaDriverPath)
                .clusterDir(clusterDir)
                .terminationHook(() -> isTerminated.set(true))
                .deleteDirOnStart(isCleanStart));

        clusteredServiceContainer = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .aeronDirectoryName(mediaDriverPath)
                .clusteredServiceDir(serviceDir)
                .idleStrategySupplier(() -> new SleepingMillisIdleStrategy(1))
                .clusteredService(this)
                .terminationHook(() -> {})
                .errorHandler(Throwable::printStackTrace)
                .deleteDirOnStart(isCleanStart));

        this.cleanOnClose = cleanOnClose;
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriverPath));

        memberStatusSubscriptions = new Subscription[members.length];
        memberStatusAdapters = new MemberStatusAdapter[members.length];
        memberStatusPublications = new Publication[members.length];

        boolean activeLeader = false;

        if (!isCleanStart && this.members.length > 1 && thisMemberIndex != leaderIndex)
        {
            leaderHarnessDir = new File(IoUtil.tmpDirName(), "aeron-cluster-" + leaderIndex);
            final String leaderMediaDriverPath = new File(leaderHarnessDir, "driver").getPath();
            final File leaderClusterDir = new File(leaderHarnessDir, "aeron-cluster");
            final File leaderArchiveDir = new File(leaderHarnessDir, "aeron-archive");
            final File leaderServiceDir = new File(leaderHarnessDir, "clustered-service");

            if (leaderClusterDir.exists() && leaderArchiveDir.exists())
            {
                activeLeader = true;

                final MediaDriver.Context mediaDriverContext = new MediaDriver.Context();
                final Archive.Context archiveContext = new Archive.Context();
                final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context();
                final ClusteredServiceContainer.Context containerContext = new ClusteredServiceContainer.Context();
                final ClusterMember leader = this.members[leaderIndex];

                ChannelUri channelUri = ChannelUri.parse(archiveContext.controlChannel());
                channelUri.put(ENDPOINT_PARAM_NAME, leader.archiveEndpoint());
                archiveContext.controlChannel(channelUri.toString());

                channelUri = ChannelUri.parse(consensusModuleContext.memberStatusChannel());
                channelUri.put(ENDPOINT_PARAM_NAME, leader.memberFacingEndpoint());
                consensusModuleContext.memberStatusChannel(channelUri.toString());

                leaderCluster = ClusteredMediaDriver.launch(
                    mediaDriverContext
                        .aeronDirectoryName(leaderMediaDriverPath)
                        .warnIfDirectoryExists(false)
                        .threadingMode(ThreadingMode.SHARED)
                        .termBufferSparseFile(true)
                        .errorHandler(Throwable::printStackTrace)
                        .dirDeleteOnStart(true),
                    archiveContext
                        .aeronDirectoryName(leaderMediaDriverPath)
                        .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                        .threadingMode(ArchiveThreadingMode.SHARED)
                        .archiveDir(leaderArchiveDir)
                        .deleteArchiveOnStart(false),
                    consensusModuleContext
                        .clusterMembers(context.clusterMembers())
                        .appointedLeaderId(leaderIndex)
                        .aeronDirectoryName(leaderMediaDriverPath)
                        .clusterDir(leaderClusterDir)
                        .terminationHook(() -> isTerminated.set(true))
                        .deleteDirOnStart(false));

                leaderContainer = ClusteredServiceContainer.launch(
                    containerContext
                        .aeronDirectoryName(leaderMediaDriverPath)
                        .clusteredServiceDir(leaderServiceDir)
                        .idleStrategySupplier(() -> new SleepingMillisIdleStrategy(1))
                        .clusteredService(this)
                        .terminationHook(() -> {})
                        .errorHandler(Throwable::printStackTrace)
                        .deleteDirOnStart(false));
            }
        }

        for (int i = 0; i < members.length; i++)
        {
            if (context.clusterMemberId() != members[i].id())
            {
                final ChannelUri memberStatusUri = ChannelUri.parse(context.memberStatusChannel());
                memberStatusUri.put(ENDPOINT_PARAM_NAME, members[i].memberFacingEndpoint());

                final int statusStreamId = context.memberStatusStreamId();

                memberStatusSubscriptions[i] =
                    aeron.addSubscription(memberStatusUri.toString(), statusStreamId);

                memberStatusAdapters[i] = new MemberStatusAdapter(
                    memberStatusSubscriptions[i], memberStatusListeners[i]);
                memberStatusPublications[i] =
                    aeron.addExclusivePublication(context.memberStatusChannel(), context.memberStatusStreamId());

                idleStrategy.reset();
                while (!memberStatusSubscriptions[i].isConnected())
                {
                    idleStrategy.idle();
                }
            }
            else
            {
                if (i != thisMemberIndex)
                {
                    throw new IllegalStateException("this member index not equal to members array element");
                }
            }

            if (members[i].id() == context.appointedLeaderId() && i != leaderIndex)
            {
                throw new IllegalStateException("leader index not equal to members array element");
            }
        }
    }

    public void close()
    {
        CloseHelper.close(leaderContainer);
        CloseHelper.close(leaderCluster);

        CloseHelper.close(clusteredServiceContainer);
        CloseHelper.close(clusteredMediaDriver);
        CloseHelper.close(aeron);

        if (cleanOnClose)
        {
            deleteDirectories();
        }
    }

    public void deleteDirectories()
    {
        if (null != clusteredServiceContainer)
        {
            clusteredServiceContainer.context().deleteDirectory();
        }

        if (null != clusteredMediaDriver)
        {
            clusteredMediaDriver.mediaDriver().context().deleteAeronDirectory();
            clusteredMediaDriver.archive().context().deleteArchiveDirectory();
            clusteredMediaDriver.consensusModule().context().deleteDirectory();
        }

        if (null != leaderContainer)
        {
            leaderContainer.context().deleteDirectory();
        }

        if (null != leaderCluster)
        {
            leaderCluster.mediaDriver().context().deleteAeronDirectory();
            leaderCluster.archive().context().deleteArchiveDirectory();
            leaderCluster.consensusModule().context().deleteDirectory();
        }

        if (null != leaderHarnessDir)
        {
            IoUtil.delete(leaderHarnessDir, true);
        }

        IoUtil.delete(harnessDir, true);
    }

    public Aeron aeron()
    {
        return aeron;
    }

    public ClusterMember member(final int index)
    {
        return members[index];
    }

    public int pollMemberStatusAdapters(final int index)
    {
        if (null != memberStatusAdapters[index])
        {
            return memberStatusAdapters[index].poll();
        }

        return 0;
    }

    public void awaitMemberStatusMessage(final int index)
    {
        idleStrategy.reset();
        while (memberStatusAdapters[index].poll() == 0)
        {
            idleStrategy.idle();
        }
    }

    public Publication memberStatusPublication(final int index)
    {
        return memberStatusPublications[index];
    }

    public MemberStatusPublisher memberStatusPublisher()
    {
        return memberStatusPublisher;
    }

    public void awaitServiceOnStart()
    {
        idleStrategy.reset();
        while (!serviceOnStart.get())
        {
            idleStrategy.idle();
        }
    }

    public void awaitServiceOnMessageCounter(final int value)
    {
        idleStrategy.reset();
        while (serviceOnMessageCounter.get() < value)
        {
            idleStrategy.idle();
        }
    }

    public void onStart(final Cluster cluster)
    {
        service.onStart(cluster);
        serviceOnStart.lazySet(true);
    }

    public void onSessionOpen(final ClientSession session, final long timestampMs)
    {
        service.onSessionOpen(session, timestampMs);
    }

    public void onSessionClose(final ClientSession session, final long timestampMs, final CloseReason closeReason)
    {
        service.onSessionClose(session, timestampMs, closeReason);
    }

    public void onSessionMessage(
        final long clusterSessionId,
        final long correlationId,
        final long timestampMs,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        service.onSessionMessage(clusterSessionId, correlationId, timestampMs, buffer, offset, length, header);
        serviceOnMessageCounter.getAndIncrement();
    }

    public void onTimerEvent(final long correlationId, final long timestampMs)
    {
        service.onTimerEvent(correlationId, timestampMs);
    }

    public void onTakeSnapshot(final Publication snapshotPublication)
    {
        service.onTakeSnapshot(snapshotPublication);
    }

    public void onLoadSnapshot(final Image snapshotImage)
    {
        service.onLoadSnapshot(snapshotImage);
    }

    public void onReplayBegin()
    {
        service.onReplayBegin();
    }

    public void onReplayEnd()
    {
        service.onReplayEnd();
    }

    public void onRoleChange(final Cluster.Role newRole)
    {
        service.onRoleChange(newRole);
    }

    public void onReady()
    {
        service.onReady();
    }

    public static long makeRecordingLog(
        final int numMessages,
        final int maxMessageLength,
        final Random random,
        final ConsensusModule.Context context)
    {
        try (ConsensusModuleHarness harness = new ConsensusModuleHarness(
            context,
            new StubClusteredService(),
            null,
            true,
            false))
        {
            harness.awaitServiceOnStart();

            final AeronCluster.Context clusterContext = new AeronCluster.Context()
                .aeronDirectoryName(harness.aeron().context().aeronDirectoryName())
                .lock(new NoOpLock());

            try (AeronCluster aeronCluster = AeronCluster.connect(clusterContext))
            {
                final SessionDecorator sessionDecorator = new SessionDecorator(aeronCluster.clusterSessionId());
                final Publication publication = aeronCluster.ingressPublication();
                final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer(maxMessageLength);

                for (int i = 0; i < numMessages; i++)
                {
                    final long messageCorrelationId = aeronCluster.context().aeron().nextCorrelationId();
                    final int length = null == random ? maxMessageLength : random.nextInt(maxMessageLength);
                    msgBuffer.putInt(0, i);

                    while (true)
                    {
                        final long result = sessionDecorator.offer(
                            publication, messageCorrelationId, msgBuffer, 0, length);
                        if (result > 0)
                        {
                            break;
                        }

                        checkOfferResult(result);
                        TestUtil.checkInterruptedStatus();

                        Thread.yield();
                    }
                }

                harness.awaitServiceOnMessageCounter(numMessages);

                return publication.position() + 96; // 96 is for the close session appended at the end.
            }
        }
    }

    public static MemberStatusListener printMemberStatusMixIn(
        final PrintStream stream, final MemberStatusListener nextListener)
    {
        return new MemberStatusListener()
        {
            public void onRequestVote(
                final long candidateTermId,
                final long lastBaseLogPosition,
                final long lastTermPosition,
                final int candidateId)
            {
                stream.format(
                    "onRequestVote %d %d %d %d%n", candidateTermId, lastBaseLogPosition, lastTermPosition, candidateId);

                nextListener.onRequestVote(candidateTermId, lastBaseLogPosition, lastTermPosition, candidateId);
            }

            public void onVote(
                final long candidateTermId,
                final int candidateMemberId,
                final int followerMemberId,
                final boolean vote)
            {
                stream.format("onVote %d %d %d %s%n", candidateTermId, candidateMemberId, followerMemberId, vote);

                nextListener.onVote(candidateTermId, candidateMemberId, followerMemberId, vote);
            }

            public void onAppendedPosition(
                final long termPosition, final long leadershipTermId, final int followerMemberId)
            {
                stream.format("onAppendedPosition %d %d %d%n", termPosition, leadershipTermId, followerMemberId);
                nextListener.onAppendedPosition(termPosition, leadershipTermId, followerMemberId);
            }

            public void onCommitPosition(
                final long termPosition, final long leadershipTermId, final int leaderMemberId, final int logSessionId)
            {
                stream.format(
                    "onCommitPosition %d %d %d %d%n", termPosition, leadershipTermId, leaderMemberId, logSessionId);
                nextListener.onCommitPosition(termPosition, leadershipTermId, leaderMemberId, logSessionId);
            }
        };
    }

    public static MemberStatusListener[] printMemberStatusMixIn(
        final PrintStream stream, final MemberStatusListener[] listeners)
    {
        final MemberStatusListener[] printMixIns = new MemberStatusListener[listeners.length];

        for (int i = 0; i < listeners.length; i++)
        {
            printMixIns[i] = printMemberStatusMixIn(stream, listeners[i]);
        }

        return printMixIns;
    }

    private static void checkOfferResult(final long result)
    {
        if (result == Publication.NOT_CONNECTED ||
            result == Publication.CLOSED ||
            result == Publication.MAX_POSITION_EXCEEDED)
        {
            throw new IllegalStateException("Unexpected publication state: " + result);
        }
    }
}
