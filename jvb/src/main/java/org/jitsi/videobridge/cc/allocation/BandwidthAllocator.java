/*
 * Copyright @ 2015 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.cc.allocation;

import edu.umd.cs.findbugs.annotations.*;
import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.stats.EndpointConnectionStats;
import org.jitsi.nlj.stats.PacketStreamStats;
import org.jitsi.nlj.stats.TransceiverStats;
import org.jitsi.nlj.transform.node.incoming.IncomingSsrcStats;
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.Endpoint;
import org.jitsi.videobridge.cc.config.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import org.jitsi.videobridge.cc.allocation.SingleSourceAllocation;
import static org.jitsi.videobridge.cc.allocation.PrioritizeKt.prioritize;
import static org.jitsi.videobridge.cc.allocation.VideoConstraintsKt.prettyPrint;

/**
 *
 * @author George Politis
 */
public class BandwidthAllocator<T extends MediaSourceContainer>
{
    /**
     * Returns a boolean that indicates whether the current bandwidth estimation (in bps) has changed above the
     * configured threshold with respect to the previous bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
     * @return true if the bandwidth has changed above the configured threshold, * false otherwise.
     */
    private boolean bweChangeIsLargerThanThreshold(long previousBwe, long currentBwe)
    {
        if (previousBwe == -1 || currentBwe == -1)
        {
            return true;
        }

        // We supress re-allocation when BWE has changed less than 15% (by default) of its previous value in order to
        // prevent excessive changes during ramp-up.
        // When BWE increases it should eventually increase past the threshold because of probing.
        // When BWE decreases it is probably above the threshold because of AIMD. It's not clear to me whether we need
        // the threshold in this case.
        // In any case, there are other triggers for re-allocation, so any suppression we do here will only last up to
        // a few seconds.
        long deltaBwe = Math.abs(currentBwe - previousBwe);
        //return deltaBwe > previousBwe * BitrateControllerConfig.config.bweChangeThreshold();
        return deltaBwe > previousBwe * 0.001;

        // If, on the other hand, the bwe has decreased, we require at least a 15% drop in order to update the bitrate
        // allocation. This is an ugly hack to prevent too many resolution/UI changes in case the bridge produces too
        // low bandwidth estimate, at the risk of clogging the receiver's pipe.
        // TODO: do we still need this? Do we ever ever see BWE drop by <%15?
    }

    private final Logger logger;

    /**
     * The estimated available bandwidth in bits per second.
     */
    private long bweBps = -1;

    /**
     * Whether this bandwidth estimator has been expired. Once expired we stop periodic re-allocation.
     */
    private boolean expired = false;

    /**
     * Provide the current list of endpoints (in no particular order).
     * TODO: Simplify to avoid the weird (and slow) flow involving `endpointsSupplier` and `sortedEndpointIds`.
     */
    private final Supplier<List<T>> endpointsSupplier;

    private final Endpoint endpointGw;
    private final String endpointGwId;


    /**
     * The "effective" constraints for an endpoint indicate the maximum resolution/fps that this
     * {@link BandwidthAllocator} would allocate for this endpoint given enough bandwidth.
     *
     * They are the constraints signaled by the receiver, further reduced to 0 when the endpoint is "outside lastN".
     *
     * Effective constraints are used to signal to video senders to reduce their resolution to the minimum that
     * satisfies all receivers.
     *
     * With the multi-stream support added, the mapping is stored on a per source name basis instead of an endpoint id.
     *
     * When an endpoint falls out of the last N, the constraints of all the sources of this endpoint are reduced to 0.
     *
     * TODO Update this description when the endpoint ID signaling is removed from the JVB.
     */
    private Map<String, VideoConstraints> effectiveConstraints = Collections.emptyMap();

    private final Clock clock;

    private final EventEmitter<EventHandler> eventEmitter = new SyncEventEmitter<>();

    /**
     * Whether bandwidth allocation should be constrained to the available bandwidth (when {@code true}), or assume
     * infinite bandwidth (when {@code false}.
     */
    private final Supplier<Boolean> trustBwe;

    /**
     * The allocations settings signalled by the receiver.
     */
    private AllocationSettings allocationSettings
            = new AllocationSettings(new VideoConstraints(BitrateControllerConfig.config.thumbnailMaxHeightPx()));

    /**
     * The last time {@link BandwidthAllocator#update()} was called.
     */
    @NotNull
    private Instant lastUpdateTime;

    /**
     * The result of the bitrate control algorithm, the last time it ran.
     */
    @NotNull
    private BandwidthAllocation allocation = new BandwidthAllocation(Collections.emptySet());

    private final DiagnosticContext diagnosticContext;

    public List<SingleSourceAllocation> SSAs;

    /**
     * The task scheduled to call {@link #update()}.
     */
    private ScheduledFuture<?> updateTask = null;

    BandwidthAllocator(
            EventHandler eventHandler,
            Supplier<List<T>> endpointsSupplier,
            T endpointGw,
            Supplier<Boolean> trustBwe,
            Logger parentLogger,
            DiagnosticContext diagnosticContext,
            Clock clock)
    {
        this.logger = parentLogger.createChildLogger(BandwidthAllocator.class.getName());
        this.clock = clock;
        this.trustBwe = trustBwe;
        this.diagnosticContext = diagnosticContext;
        this.endpointGw = (Endpoint) endpointGw;
        this.endpointGwId = endpointGw.getId();
        this.endpointsSupplier = endpointsSupplier;
        eventEmitter.addHandler(eventHandler);
        // Don't trigger an update immediately, the settings might not have been configured.
        lastUpdateTime = clock.instant();
        rescheduleUpdate();
    }

    /**
     * Gets a JSON representation of the parts of this object's state that are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("trustBwe", trustBwe.get());
        debugState.put("bweBps", bweBps);
        debugState.put("allocation", allocation.getDebugState());
        debugState.put("allocationSettings", allocationSettings.toJson());
        debugState.put("effectiveConstraints", effectiveConstraints);
        return debugState;
    }

    @NotNull
    BandwidthAllocation getAllocation()
    {
        return allocation;
    }

    /**
     * Get the available bandwidth, taking into account the `trustBwe` option.
     */
    private long getAvailableBandwidth()
    {
        return trustBwe.get() ? bweBps : Long.MAX_VALUE;
    }

    /**
     * Notify the {@link BandwidthAllocator} that the estimated available bandwidth has changed.
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    void bandwidthChanged(long newBandwidthBps)
    {
        if (!bweChangeIsLargerThanThreshold(bweBps, newBandwidthBps))
        {
            logger.debug(() -> "New bandwidth (" + newBandwidthBps
                    + ") is not significantly " +
                    "changed from previous estimate (" + bweBps + "), ignoring");
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bandwidth allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        }
        else
        {
            logger.debug(() -> "new bandwidth is " + newBandwidthBps + ", updating");

            bweBps = newBandwidthBps;
            update();
        }
    }

    /**
     * Updates the allocation settings and calculates a new bitrate {@link BandwidthAllocation}.
     * @param allocationSettings the new allocation settings.
     */
    void update(AllocationSettings allocationSettings)
    {
        this.allocationSettings = allocationSettings;
        update();
    }

    /**
     * Runs the bandwidth allocation algorithm, and fires events if the result is different from the previous result.
     */
    synchronized void update()
    {
        if (expired)
        {
            return;
        }

        lastUpdateTime = clock.instant();

        // Declare variables for flow branching below
        BandwidthAllocation newAllocation;
        Map<String, VideoConstraints> oldEffectiveConstraints;

        // Order the sources by selection, followed by Endpoint's speech activity.
        List<MediaSourceDesc> sources
                = endpointsSupplier.get()
                .stream()
                .flatMap(endpoint -> Arrays.stream(endpoint.getMediaSources()))
                .collect(Collectors.toList());
        List<MediaSourceDesc> sortedSources = prioritize(sources, getSelectedSources());

        // Extract and update the effective constraints.
        oldEffectiveConstraints = effectiveConstraints;
        effectiveConstraints = PrioritizeKt.getEffectiveConstraints(sortedSources, allocationSettings);
        logger.trace(() ->
                "Allocating: sortedSources="
                        + sortedSources.stream().map(MediaSourceDesc::getSourceName).collect(Collectors.joining(","))
                        + " effectiveConstraints=" + prettyPrint(effectiveConstraints));

        // Compute the bandwidth allocation.
        newAllocation = allocate(sortedSources);

        eventEmitter.fireEvent(handler ->
        {
            handler.sourceListChanged(sortedSources);
            return Unit.INSTANCE;
        });

        boolean allocationChanged = !allocation.isTheSameAs(newAllocation);
        if (allocationChanged)
        {
            eventEmitter.fireEvent(handler -> {
                handler.allocationChanged(newAllocation);
                return Unit.INSTANCE;
            });
        }
        allocation = newAllocation;

        boolean effectiveConstraintsChanged = !effectiveConstraints.equals(oldEffectiveConstraints);
        logger.trace(() -> "Finished allocation: allocationChanged=" + allocationChanged
                + " effectiveConstraintsChanged=" + effectiveConstraintsChanged);
        if (effectiveConstraintsChanged)
        {
            eventEmitter.fireEvent(handler ->
            {
                handler.effectiveVideoConstraintsChanged(oldEffectiveConstraints, effectiveConstraints);
                return Unit.INSTANCE;
            });
        }
    }

    private List<String> getSelectedSources()
    {
        // On-stage sources are considered selected (with higher priority).
        List<String> selectedSources = new ArrayList<>(allocationSettings.getOnStageSources());
        allocationSettings.getSelectedSources().forEach(selectedSource ->
        {
            if (!selectedSources.contains(selectedSource))
            {
                selectedSources.add(selectedSource);
            }
        });
        return selectedSources;
    }

    /**
     * Implements the bandwidth allocation algorithm for the given ordered list of media sources.
     *
     * The new version which works with multiple streams per endpoint.
     *
     * @param conferenceMediaSources the list of endpoint media sources in order of priority to allocate for.
     * @return the new {@link BandwidthAllocation}.
     */
    private synchronized @NotNull BandwidthAllocation allocate(List<MediaSourceDesc> conferenceMediaSources)
    {
        List<SingleSourceAllocation> sourceBitrateAllocations = createAllocations(conferenceMediaSources);
        SSAs = sourceBitrateAllocations;

        if (sourceBitrateAllocations.isEmpty())
        {
            return new BandwidthAllocation(Collections.emptySet());
        }

        JSONObject data = statInfoCollector();
        Map<String, Integer> mappingRL = new HashMap<String, Integer>();

        String rlServer = "https://141.223.181.223:9005/predict"; //RL Server Request address
        String rlTest = callRL(rlServer, data);

        JSONObject targetInfo = new JSONObject();

        try {
            // JSON Pasrsing : Move from parse function inside of allocate function
            // targetInfo = parseJSON(rlTest);
            JSONParser jsonParser = new JSONParser();
            Object obj = jsonParser.parse(rlTest);
            JSONObject jsonObj = (JSONObject) obj;
            targetInfo = jsonObj;
        } catch (Exception e) {
            e.printStackTrace();
        }

        logger.info("##### TEST data ##### : " + data.toString());
        logger.info("##### TEST ##### : " + targetInfo);

        Integer useRL = 0;
        if (targetInfo != null) {
            useRL = Integer.valueOf(targetInfo.get("useRL").toString()).intValue();
        }
        logger.info("##### useRL ##### : " + useRL);

        /**
         * Todo: collector <---> RL model
         * For communication
         * */
        // Should implement the communication RL server. Send the collected data to RL server and receive back all targetIdx.
        for(SingleSourceAllocation ssa: sourceBitrateAllocations) {
            String tmpId = ssa.getEndpointId();
            int targetIdx = 5;
            try{
                if(targetInfo != null) {
                    targetIdx = Integer.valueOf(targetInfo.get(tmpId).toString()).intValue();
                }
            }catch (Exception e){
                e.printStackTrace();
            }
            mappingRL.put(tmpId, targetIdx);
        }

        long remainingBandwidth = getAvailableBandwidth();
        long oldRemainingBandwidth = -1;

        boolean oversending = false;

        if (useRL == 1){
            for (int i = 0; i < sourceBitrateAllocations.size(); i++)
            {
                SingleSourceAllocation sourceBitrateAllocation = sourceBitrateAllocations.get(i);
                if (sourceBitrateAllocation.getConstraints().isDisabled())
                {
                    continue;
                }
                int target = -1;
                if (mappingRL.containsKey(sourceBitrateAllocation.getEndpointId())){
                    target = mappingRL.get(sourceBitrateAllocation.getEndpointId());
                }

                // In stage view improve greedily until preferred, in tile view go step-by-step.
                //remainingBandwidth -= sourceBitrateAllocation.improve(remainingBandwidth, i == 0);
                remainingBandwidth -= sourceBitrateAllocation.rlApply(target,remainingBandwidth, i == 0);
                if (remainingBandwidth < 0)
                {
                    oversending = true;
                }

                // In stage view, do not allocate bandwidth for thumbnails until the on-stage reaches "preferred".
                // This prevents enabling thumbnail only to disable them when bwe slightly increases allowing on-stage
                // to take more.
                if (sourceBitrateAllocation.isOnStage() && !sourceBitrateAllocation.hasReachedPreferred())
                {
                    break;
                }
            }
        }
        else {
            while (oldRemainingBandwidth != remainingBandwidth)
            {
                oldRemainingBandwidth = remainingBandwidth;

                for (int i = 0; i < sourceBitrateAllocations.size(); i++)
                {
                    SingleSourceAllocation sourceBitrateAllocation = sourceBitrateAllocations.get(i);
                    if (sourceBitrateAllocation.getConstraints().isDisabled())
                    {
                        continue;
                    }

                    // In stage view improve greedily until preferred, in tile view go step-by-step.
                    remainingBandwidth -= sourceBitrateAllocation.improve(remainingBandwidth, i == 0);
                    if (remainingBandwidth < 0)
                    {
                        oversending = true;
                    }

                    // In stage view, do not allocate bandwidth for thumbnails until the on-stage reaches "preferred".
                    // This prevents enabling thumbnail only to disable them when bwe slightly increases allowing on-stage
                    // to take more.
                    if (sourceBitrateAllocation.isOnStage() && !sourceBitrateAllocation.hasReachedPreferred())
                    {
                        break;
                    }
                }
            }
        }

        // The sources which are in lastN, and are sending video, but were suspended due to bwe.
        List<String> suspendedIds = sourceBitrateAllocations.stream()
                .filter(SingleSourceAllocation::isSuspended)
                .map(SingleSourceAllocation::getMediaSource)
                .map(MediaSourceDesc::getSourceName)
                .collect(Collectors.toList());
        if (!suspendedIds.isEmpty())
        {
            logger.info("Sources were suspended due to insufficient bandwidth (bwe="
                    + getAvailableBandwidth() + " bps): " + String.join(",", suspendedIds));
        }

        Set<SingleAllocation> allocations = new HashSet<>();

        long targetBps = 0, idealBps = 0;
        for (SingleSourceAllocation sourceBitrateAllocation : sourceBitrateAllocations) {
            //logger.info("##### SingleAllocation ##### : " + sourceBitrateAllocation.getResult());
            //logger.info("##### selectLayers ##### : " + sourceBitrateAllocation.getLayers());
            //logger.info("##### allLayers ##### : " + sourceBitrateAllocation.getAllLayers());
            allocations.add(sourceBitrateAllocation.getResult());
            targetBps += sourceBitrateAllocation.getTargetBitrate();
            idealBps += sourceBitrateAllocation.getIdealBitrate();
        }
        allocations.forEach(element -> logger.info(element));
        return new BandwidthAllocation(allocations, oversending, idealBps, targetBps, suspendedIds);
    }

    private String callRL(String strUrl, JSONObject data) {
        RestCallRL restCall = new RestCallRL();
        String url = strUrl;
        String  result = restCall.callPOST(url, data);
        return result;
    }

    synchronized JSONObject statInfoCollector() {
        List<T> endpointList = this.endpointsSupplier.get();
        JSONObject result = new JSONObject();
        JSONObject eps = new JSONObject();

        Endpoint itself = endpointGw;
        //logger.info("##### Endpint GW ##### : " + endpointGw);

        double rttSumMs = 0;
        long rttCount = 0;
        double epJitterSumMs = 0;
        int epJitterCount = 0;

        long bwe = 0;

        TransceiverStats transceiverStats = itself.getTransceiver().getTransceiverStats();
        IncomingStatisticsSnapshot incomingStats = transceiverStats.getRtpReceiverStats().getIncomingStats();

        for (IncomingSsrcStats.Snapshot ssrcStats : incomingStats.getSsrcStats().values())
        {
            double ssrcJitter = ssrcStats.getJitter();
            if (ssrcJitter != 0)
            {
                epJitterSumMs += Math.abs(ssrcJitter);
                epJitterCount++;
            }
        }

        EndpointConnectionStats.Snapshot endpointConnectionStats
                = transceiverStats.getEndpointConnectionStats();
        double endpointRtt = endpointConnectionStats.getRtt();
        if (endpointRtt > 0)
        {
            rttSumMs += endpointRtt;
            rttCount++;
        }
        Long pkt_lost = endpointConnectionStats.getOutgoingLossStats().getPacketsLost();
        Long pkt_received = endpointConnectionStats.getOutgoingLossStats().getPacketsReceived();

        bwe = this.getAvailableBandwidth();

        for(T ep : endpointList) {
            JSONObject epStats = new JSONObject();
            Endpoint endpoint = (Endpoint) ep;

            epStats.put("jitter_ms", epJitterCount > 0 ? epJitterSumMs/epJitterCount : 0);
            epStats.put("round_trip_time_ms", rttCount > 0 ? rttSumMs/rttCount : 0);
            epStats.put("pkt_lost", pkt_lost);
            epStats.put("pkt_received", pkt_received);


            JSONObject videoConstraints = new JSONObject();
            Map<String, VideoConstraints> videoConstraintsMap = allocationSettings
                    .getVideoConstraints();
            VideoConstraints vc = videoConstraintsMap.get(endpoint.getId());
            if(vc != null) {
                videoConstraints.put("maxHeight", vc.getMaxHeight());
                videoConstraints.put("maxFramerte", vc.getMaxFrameRate());
            }
            epStats.put("video_constraints", videoConstraints);

            List<LayerSnapshot> layerInfos = getAllLayerBps().get(endpoint.getId());
            JSONObject layerAllStats = new JSONObject();
            if(layerInfos != null){
                for(LayerSnapshot layer: layerInfos) {
                    RtpLayerDesc lDesc = layer.component1();
                    Double bitrate = layer.component2();

                    JSONObject layerInfoStats = new JSONObject();
                    layerInfoStats.put("temporal_id", lDesc.getTid());
                    layerInfoStats.put("spatial_id", lDesc.getSid());
                    layerInfoStats.put("height", lDesc.getHeight());
                    layerInfoStats.put("framerate", lDesc.getFrameRate());
                    layerInfoStats.put("bitrate", bitrate);
                    layerAllStats.put(lDesc.getLocalIndex(), layerInfoStats);
                }
                epStats.put("layers", layerAllStats);
            }

            Set<SingleAllocation> allocs = this.getAllocation().getAllocations();
            if(allocs != null) {
                for(SingleAllocation alloc : allocs) {
                    if (alloc.getEndpointId().equals(ep.getId())) {
                        JSONObject allocEidStats = new JSONObject();
                        if (alloc.getTargetLayer() != null) {
                            //logger.info("########## " + endpoint.getId() + "'s BA -  Endpoint : " + alloc.getEndpointId() + ", TargetLayer : " + alloc.getTargetLayer().toString() + ", IdealLayer : " + alloc.getIdealLayer().toString() + ", targetIdx : " + alloc.getTargetLayer().getIndex());
                            JSONObject allocTargetStats = new JSONObject();
                            allocTargetStats.put("target_quality", alloc.getTargetLayer().getIndex());
                            allocTargetStats.put("target_temporal_id", alloc.getTargetLayer().getTid());
                            allocTargetStats.put("target_spatial_id", alloc.getTargetLayer().getSid());
                            allocTargetStats.put("target_framerate", alloc.getTargetLayer().getFrameRate());
                            allocTargetStats.put("target_height", alloc.getTargetLayer().getHeight());
                            allocEidStats.put("target", allocTargetStats);

                            JSONObject allocIdealStats = new JSONObject();
                            allocIdealStats.put("ideal_quality", alloc.getIdealLayer().getIndex());
                            allocIdealStats.put("ideal_temporal_id", alloc.getIdealLayer().getTid());
                            allocIdealStats.put("ideal_spatial_id", alloc.getIdealLayer().getSid());
                            allocIdealStats.put("ideal_framerate", alloc.getIdealLayer().getFrameRate());
                            allocIdealStats.put("ideal_height", alloc.getIdealLayer().getHeight());
                            allocEidStats.put("ideal", allocIdealStats);
                        }
                        epStats.put("Allocations", allocEidStats);
                    }
                }
            }
            eps.put(endpoint.getId(), epStats);

            //endpoint.getConference().getLocalEndpoints();
        }
        JSONObject sumStats = new JSONObject();
        //logger.info("##### (" + (cnt++) + ") Available BWE: "+ aBwe + ", TargetBps: " + tBps + ", IdealBps: " + iBps);
        //Long aBwe = this.getAvailableBandwidth();
        //Long tBps = this.getAllocation().getTargetBps();
        //Long iBps = this.getAllocation().getIdealBps();
        sumStats.put("Available_BW", bwe);
        sumStats.put("timestamp", clock.millis());
        //sumStats.put("Total_targetBps", tBps);
        //sumStats.put("Total_idealBps", iBps);
        eps.put("Summary", sumStats);

        result.put(endpointGwId, eps);

        // logger.info("##### Check : " + result);
        return result;
    }


    /**
     * Query whether this allocator is forwarding a source from a given endpoint, as of its
     * most recent allocation decision.
     */
    public boolean isForwarding(String endpointId)
    {
        return allocation.isForwarding(endpointId);
    }

    /**
     * Query whether the allocator has non-zero effective constraints for the given endpoint or source.
     */
    public boolean hasNonZeroEffectiveConstraints(String endpointId)
    {
        VideoConstraints constraints = effectiveConstraints.get(endpointId);
        if (constraints == null)
        {
            return false;
        }
        return !constraints.isDisabled();
    }

    // The new version which works with multiple streams per endpoint.
    private synchronized @NotNull List<SingleSourceAllocation> createAllocations(
            List<MediaSourceDesc> conferenceMediaSources)
    {
        // Init.
        List<SingleSourceAllocation> sourceBitrateAllocations = new ArrayList<>(conferenceMediaSources.size());

        for (MediaSourceDesc source : conferenceMediaSources)
        {
            sourceBitrateAllocations.add(
                new SingleSourceAllocation(
                        source.getOwner(),
                        source,
                        // Note that we use the effective constraints and not the receiver's constraints
                        // directly. This means we never even try to allocate bitrate to sources "outside
                        // lastN". For example, if LastN=1 and the first endpoint sends a non-scalable
                        // stream with bitrate higher that the available bandwidth, we will forward no
                        // video at all instead of going to the second endpoint in the list.
                        // I think this is not desired behavior. However, it is required for the "effective
                        // constraints" to work as designed.
                        effectiveConstraints.get(source.getSourceName()),
                        allocationSettings.getOnStageSources().contains(source.getSourceName()),
                        diagnosticContext,
                        clock,
                        logger));
        }

        return sourceBitrateAllocations;
    }

    /**
     * Expire this bandwidth allocator.
     */
    void expire()
    {
        expired = true;
        ScheduledFuture<?> updateTask = this.updateTask;
        if (updateTask != null)
        {
            updateTask.cancel(false);
        }
    }

    /**
     * Submits a call to `update` in a CPU thread if bandwidth allocation has not been performed recently.
     *
     * Also, re-schedule the next update in at most {@code maxTimeBetweenCalculations}. This should only be run
     * in the constructor or in the scheduler thread, otherwise it will schedule multiple tasks.
     */
    private void rescheduleUpdate()
    {
        if (expired)
        {
            return;
        }

        Duration timeSinceLastUpdate = Duration.between(lastUpdateTime, clock.instant());
        Duration period = BitrateControllerConfig.config.maxTimeBetweenCalculations();
        long delayMs;
        if (timeSinceLastUpdate.compareTo(period) > 0)
        {
            logger.debug("Running periodic re-allocation.");
            TaskPools.CPU_POOL.execute(this::update);

            delayMs = period.toMillis();
        }
        else
        {
            delayMs = period.minus(timeSinceLastUpdate).toMillis();
        }

        // Add 5ms to avoid having to re-schedule right away. This increases the average period at which we
        // re-allocate by an insignificant amount.
        updateTask = TaskPools.SCHEDULED_POOL.schedule(
                this::rescheduleUpdate,
                delayMs + 5,
                TimeUnit.MILLISECONDS);
    }

    public interface EventHandler
    {
        default void allocationChanged(@NotNull BandwidthAllocation allocation) {}
        default void effectiveVideoConstraintsChanged(
                @NotNull Map<String, VideoConstraints> oldEffectiveConstraints,
                @NotNull Map<String, VideoConstraints> newEffectiveConstraints) {}
        default void sourceListChanged(@NotNull List<MediaSourceDesc> sourceList) {}
    }
    
    public Map<String, Long> getTargetLayerBps() {
        Map<String, Long> result = new HashMap<>();
        for(SingleSourceAllocation ssa: SSAs) {
            result.put(ssa.getEndpointId(), ssa.getTargetBitrate());
        }
        return result;
    }

    public Map<String, List<LayerSnapshot>> getAllLayerBps() {
        Map<String, List<LayerSnapshot>> result= new HashMap<String, List<LayerSnapshot>>();
        for(SingleSourceAllocation ssa: SSAs) {
            result.put(ssa.getEndpointId(), ssa.getAllLayers());
        }
        return result;
    }
}
