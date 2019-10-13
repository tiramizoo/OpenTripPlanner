package org.opentripplanner.ext.siri.updater;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.BooleanUtils;
import org.opentripplanner.ext.siri.SiriFuzzyTripMatcher;
import org.opentripplanner.ext.siri.SiriTimetableSnapshotSource;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.updater.GraphUpdaterManager;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.opentripplanner.updater.JsonConfigurable;
import org.opentripplanner.updater.PollingGraphUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.ServiceDelivery;
import uk.org.siri.siri20.Siri;
import uk.org.siri.siri20.VehicleMonitoringDeliveryStructure;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Update OTP stop time tables from some (realtime) source
 *
 * Usage example ('rt' name is an example) in file 'Graph.properties':
 *
 * <pre>
 * rt.type = stop-time-updater
 * rt.frequencySec = 60
 * rt.sourceType = gtfs-http
 * rt.url = http://host.tld/path
 * rt.feedId = TA
 * </pre>
 *
 */
public class SiriVMUpdater extends PollingGraphUpdater {
    private static final Logger LOG = LoggerFactory.getLogger(SiriVMUpdater.class);

    /**
     * Parent update manager. Is used to execute graph writer runnables.
     */
    protected GraphUpdaterManager updaterManager;

    /**
     * Update streamer
     */
    private VehicleMonitoringSource updateSource;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Integer logFrequency;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Integer maxSnapshotFrequency;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Boolean purgeExpiredData;

    /**
     * Feed id that is used for the trip ids in the TripUpdates
     */
    private String feedId;

    /**
     * Set only if we should attempt to match the trip_id from other data in TripDescriptor
     */
    private SiriFuzzyTripMatcher siriFuzzyTripMatcher;

    @Override
    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
        this.updaterManager = updaterManager;
    }

    @Override
    public void configurePolling(Graph graph, JsonNode config) throws Exception {
        // Create update streamer from preferences
        feedId = config.path("feedId").asText("");
        String sourceType = config.path("sourceType").asText();

        updateSource = new SiriVMHttpTripUpdateSource();

        // Configure update source
        if (updateSource instanceof JsonConfigurable) {
            ((JsonConfigurable) updateSource).configure(graph, config);
        } else {
            throw new IllegalArgumentException(
                    "Unknown update streamer source type: " + sourceType);
        }

        int logFrequency = config.path("logFrequency").asInt(-1);
        if (logFrequency >= 0) {
            this.logFrequency = logFrequency;
        }
        int maxSnapshotFrequency = config.path("maxSnapshotFrequencyMs").asInt(-1);
        if (maxSnapshotFrequency >= 0) {
            this.maxSnapshotFrequency = maxSnapshotFrequency;
        }
        this.purgeExpiredData = config.path("purgeExpiredData").asBoolean(true);
        if (config.path("fuzzyTripMatching").asBoolean(true)) {
            this.siriFuzzyTripMatcher = new SiriFuzzyTripMatcher(graph.index);
        }

        blockReadinessUntilInitialized = config.path("blockReadinessUntilInitialized").asBoolean(false);

        LOG.info("Creating stop time updater (SIRI VM) running every {} seconds : {}", pollingPeriodSeconds, updateSource);
    }

    @Override
    public void setup(Graph graph) throws InterruptedException, ExecutionException {
        // Create a realtime data snapshot source and wait for runnable to be executed
        // Only create a realtime data snapshot source if none exists already
        SiriTimetableSnapshotSource snapshotSource = graph.getOrSetupTimetableSnapshotProvider(
                SiriTimetableSnapshotSource::new
        );
        // Set properties of realtime data snapshot source
        if (logFrequency != null) {
            snapshotSource.logFrequency = logFrequency;
        }
        if (maxSnapshotFrequency != null) {
            snapshotSource.maxSnapshotFrequency = maxSnapshotFrequency;
        }
        if (purgeExpiredData != null) {
            snapshotSource.purgeExpiredData = purgeExpiredData;
        }
        if (siriFuzzyTripMatcher != null) {
            siriFuzzyTripMatcher = new SiriFuzzyTripMatcher(graph.index);
        }
    }

    /**
     * Repeatedly makes blocking calls to an UpdateStreamer to retrieve new stop time updates, and
     * applies those updates to the graph.
     */
    @Override
    public void runPolling() throws Exception {
        boolean moreData = false;
        do {
            // Get update lists from update source
            Siri updates = updateSource.getUpdates();
            if (updates != null) {
                boolean fullDataset = updateSource.getFullDatasetValueOfLastUpdates();
                ServiceDelivery serviceDelivery = updates.getServiceDelivery();
                // Use isTrue in case isMoreData returns null. Mark the updater as primed after last page of updates.
                moreData = BooleanUtils.isTrue(serviceDelivery.isMoreData());
                final boolean markPrimed = !moreData;
                List<VehicleMonitoringDeliveryStructure> vmds = serviceDelivery.getVehicleMonitoringDeliveries();
                if (vmds != null) {
                    updaterManager.execute(graph -> {
                        SiriTimetableSnapshotSource snapshotSource;
                        // TODO OTP2 - This is not thread safe, we should inject the snapshotSource on this class,
                        //           - it will work because the snapshotSource is created already.
                        snapshotSource = graph.getOrSetupTimetableSnapshotProvider(SiriTimetableSnapshotSource::new);
                        if (snapshotSource != null) {
                            snapshotSource.applyVehicleMonitoring(graph, feedId, fullDataset, vmds);
                        } else {
                            LOG.error("Could not find realtime data snapshot source in graph."
                                    + " The following updates are not applied: {}", updates);
                        }
                        if (markPrimed) primed = true;
                    });
                }
                // Use isTrue just in case isMoreData returns null
                moreData = BooleanUtils.isTrue(serviceDelivery.isMoreData());
            }
        } while (moreData);
    }

    @Override
    public void teardown() {
    }

    public String toString() {
        String s = (updateSource == null) ? "NONE" : updateSource.toString();
        return "Polling SIRI VM updater with update source = " + s;
    }

}
