package org.opentripplanner.routing.edgetype;

import java.util.BitSet;

import java.util.Locale;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Trip;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.ServiceDay;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TransferTable;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.PatternStopVertex;
import org.opentripplanner.routing.vertextype.TransitStopArrive;
import org.opentripplanner.routing.vertextype.TransitStopDepart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.locationtech.jts.geom.LineString;

/**
 * Models boarding or alighting a vehicle - that is to say, traveling from a state off 
 * vehicle to a state on vehicle. When traversed forward on a boarding or backwards on an 
 * alighting, the the resultant state has the time of the next departure, in addition the pattern
 * that was boarded. When traversed backward on a boarding or forward on an alighting, the result
 * state is unchanged. A boarding penalty can also be applied to discourage transfers. In an on
 * the fly reverse-optimization search, the overloaded traverse method can be used to give an
 * initial wait time. Also, in reverse-opimization, board costs are correctly applied.
 * 
 * This is the result of combining the classes formerly known as PatternBoard and PatternAlight.
 * 
 * @author mattwigway
 */
public class TransitBoardAlight extends TablePatternEdge implements OnboardEdge {

    private static final long serialVersionUID = 1042740795612978747L;

    private static final Logger LOG = LoggerFactory.getLogger(TransitBoardAlight.class);

    private final int stopIndex;

    private int modeMask; // TODO: via TablePatternEdge it should be possible to grab this from the pattern
   
    /** True if this edge represents boarding a vehicle, false if it represents alighting. */
    public boolean boarding;

    /** Boarding constructor (TransitStopDepart --> PatternStopVertex) */
    public TransitBoardAlight (TransitStopDepart fromStopVertex, PatternStopVertex toPatternVertex, 
            int stopIndex, TraverseMode mode) {
        super(fromStopVertex, toPatternVertex);
        this.stopIndex = stopIndex;
        this.modeMask = new TraverseModeSet(mode).getMask();
        this.boarding = true;
    }
    
    /** Alighting constructor (PatternStopVertex --> TransitStopArrive) */
    public TransitBoardAlight (PatternStopVertex fromPatternStop, TransitStopArrive toStationVertex,
            int stopIndex, TraverseMode mode) {
        super(fromPatternStop, toStationVertex);
        this.stopIndex = stopIndex;
        this.modeMask = new TraverseModeSet(mode).getMask();
        this.boarding = false;
    }
    
    /** 
     * Find the TripPattern this edge is boarding or alighting from. Overrides the general
     * method which always looks at the from-vertex.
     * @return the pattern of the to-vertex when boarding, and that of the from-vertex 
     * when alighting. 
     */
    @Override 
    public TripPattern getPattern() {
        if (boarding)
            return ((PatternStopVertex) tov).getTripPattern();
        else
            return ((PatternStopVertex) fromv).getTripPattern();
    }
                           
    public String getDirection() {
        return null;
    }

    public double getDistance() {
        return 0;
    }

    public LineString getGeometry() {
        return null;
    }

    public TraverseMode getMode() {
        return TraverseMode.LEG_SWITCH;
    }

    public String getName() {
        return boarding ? "leave street network for transit network" : 
            "leave transit network for street network";
    }
    
    @Override
    public String getName(Locale locale) {
        //TODO: localize
        return this.getName();
    }

    @Override
    public State traverse(State state0) {
        return traverse(state0, 0);
    }
    
    
    /**
     * NOTE: We do not need to check the pickup/drop off type. TransitBoardAlight edges are simply
     * not created for pick/drop type 1 (no pick/drop).
     * 
     * @param arrivalTimeAtStop TODO: clarify what this is.
     */
    public State traverse(State s0, long arrivalTimeAtStop) {
        RoutingContext rctx    = s0.getContext();
        RoutingRequest options = s0.getOptions();

        // Forbid taking shortcuts composed of two board-alight edges in a row. Also avoids spurious leg transitions.
        if (s0.backEdge instanceof TransitBoardAlight) {
            return null;
        }

        /* If the user requested a wheelchair accessible trip, check whether and this stop is not accessible. */
        if (options.wheelchairAccessible && ! getPattern().wheelchairAccessible(stopIndex)) {
            return null;
        }

        // if eligibility-restricted services are disallowed, check this route. Only supports 0/1 values.
        if (!options.flexUseEligibilityServices) {
            Route route = getPattern().route;
            if (route.hasEligibilityRestricted() && route.getEligibilityRestricted() == 1) {
                return null;
            }
        }

        /*
         * Determine whether we are going onto or off of transit. Entering and leaving transit is
         * not the same thing as boarding and alighting. When arriveBy == true, we are entering
         * transit when traversing an alight edge backward.
         */
        boolean leavingTransit = 
                ( boarding &&  options.arriveBy) || 
                (!boarding && !options.arriveBy); 

        /* TODO pull on/off transit out into two functions. */
        if (leavingTransit) { 
            /* We are leaving transit, not as much to do. */
            // When a dwell edge has been eliminated, do not alight immediately after boarding.
            // Perhaps this should be handled by PathParser.
            if (s0.getBackEdge() instanceof TransitBoardAlight) {
                return null;
            }
            StateEditor s1 = s0.edit(this);
            s1.setTripId(null);
            s1.setLastAlightedTimeSeconds(s0.getTimeSeconds());
            // Store the stop we are alighting at, for computing stop-to-stop transfer times,
            // preferences, and permissions.
            // The vertices in the transfer table are stop arrives/departs, not pattern
            // arrives/departs, so previousStop is direction-dependent.
            s1.setPreviousStop(getStop()); 
            s1.setLastPattern(this.getPattern());
            s1.setIsLastBoardAlightDeviated(isDeviated());
            if (boarding) {
                int boardingTime = options.getBoardTime(this.getPattern().mode);
                if (boardingTime != 0) {
                    // When traveling backwards the time travels also backwards
                    s1.incrementTimeInSeconds(boardingTime);
                    s1.incrementWeight(boardingTime * options.waitReluctance);
                }
            } else {
                int alightTime = options.getAlightTime(this.getPattern().mode);
                if (alightTime != 0) {
                    s1.incrementTimeInSeconds(alightTime);
                    s1.incrementWeight(alightTime * options.waitReluctance);
                    // TODO: should we have different cost for alighting and boarding compared to regular waiting?
                }
            }
            s1.incrementWeight(getExtraWeight(options));

            /* Determine the wait. */
            if (arrivalTimeAtStop > 0) { // FIXME what is this arrivalTimeAtStop?
                int wait = (int) Math.abs(s0.getTimeSeconds() - arrivalTimeAtStop);
                
                s1.incrementTimeInSeconds(wait);
                // this should only occur at the beginning
                s1.incrementWeight(wait * options.waitAtBeginningFactor);

                s1.setInitialWaitTimeSeconds(wait);

                //LOG.debug("Initial wait time set to {} in PatternBoard", wait);
            }
            
            // during reverse optimization, board costs should be applied to PatternBoards
            // so that comparable trip plans result (comparable to non-optimized plans)
            if (options.reverseOptimizing)
                s1.incrementWeight(options.getBoardCost(s0.getNonTransitMode()));

            if (options.reverseOptimizeOnTheFly) {
                TripPattern pattern = getPattern();
                int thisDeparture = s0.getTripTimes().getDepartureTime(stopIndex);
                int numTrips = getPattern().getNumScheduledTrips(); 
                int nextDeparture;

                s1.setLastNextArrivalDelta(Integer.MAX_VALUE);

                for (int tripIndex = 0; tripIndex < numTrips; tripIndex++) {
                    Timetable timetable = pattern.getUpdatedTimetable(options, s0.getServiceDay());
                    nextDeparture = timetable.getTripTimes(tripIndex).getDepartureTime(stopIndex);
        
                    if (nextDeparture > thisDeparture) {
                        s1.setLastNextArrivalDelta(nextDeparture - thisDeparture);
                        break;
                    }
                }
            }            

            s1.setBackMode(getMode());

            return s1.makeState();
        } else { 
            /* We are going onto transit and must look for a suitable transit trip on this pattern. */   
            
            /* Disallow ever re-boarding the same trip pattern. */
            if (s0.getLastPattern() == this.getPattern()) {
                return null; 
            }
            
            /* Check this pattern's mode against those allowed in the request. */
            if (!options.modes.get(modeMask)) {
                return null;
            }

            /* Check if route and/or agency are banned or whitelisted for this pattern */
            if (options.routeIsBanned(this.getPattern().route)) return null;

            /*
             * Find the next boarding/alighting time relative to the current State. Check lists of
             * transit serviceIds running yesterday, today, and tomorrow relative to the initial
             * state. Choose the closest board/alight time among trips starting yesterday, today, or
             * tomorrow. Note that we cannot skip searching on service days that have not started
             * yet: Imagine a state at 23:59 Sunday, that should take a bus departing at 00:01
             * Monday (and coded on Monday in the GTFS); disallowing Monday's departures would
             * produce a strange plan. We also can't break off the search after we find trips today.
             * Imagine a trip on a pattern at 25:00 today and another trip on the same pattern at
             * 00:30 tommorrow. The 00:30 trip should be taken, but if we stopped the search after
             * finding today's 25:00 trip we would never find tomorrow's 00:30 trip.
             */
            int bestWait = -1;
            TripTimes  bestTripTimes  = null;
            ServiceDay bestServiceDay = null;
            for (ServiceDay sd : rctx.serviceDays) {
                /* Find the proper timetable (updated or original) if there is a realtime snapshot. */
                Timetable timetable = getPattern().getUpdatedTimetable(options, sd);
                /* Skip this day/timetable if no trip in it could possibly be useful. */
                if ( ! timetable.temporallyViable(sd, s0.getTimeSeconds(), bestWait, boarding)) {
                    continue;
                }
                TripTimes tripTimes = getNextTrip(s0, sd, timetable);
                if (tripTimes != null) {
                    /* Wait is relative to departures on board and arrivals on alight. */
                    int wait = calculateWait(s0, sd, tripTimes);
                    /* A trip was found. The wait should be non-negative. */
                    if (wait < 0) {
                        LOG.error("Negative wait time when boarding.");
                    }
                    /* Track the soonest departure over all relevant schedules. */
                    if (bestWait < 0 || wait < bestWait) {
                        bestWait       = wait;
                        bestServiceDay = sd;
                        bestTripTimes  = tripTimes;
                    }
                }
            }
            if (bestWait < 0) return null; // no appropriate trip was found

            // check if this departure is banned
            if (options.bannedDepartures != null && options.bannedDepartures.matches(bestServiceDay, bestTripTimes, stopIndex))
                return null;

            Trip trip = bestTripTimes.trip;

            /* Found a trip to board. Now make the child state. */
            StateEditor s1 = s0.edit(this);
            s1.setBackMode(getMode());
            s1.setServiceDay(bestServiceDay);
            // Save the trip times in the State to ensure that router has a consistent view 
            // and constant-time access to them.
            s1.setTripTimes(bestTripTimes);
            s1.incrementTimeInSeconds(bestWait);
            s1.incrementNumBoardings();
            s1.setTripId(trip.getId());
            s1.setPreviousTrip(trip);
            s1.setZone(getPattern().getZone(stopIndex));
            s1.setRoute(trip.getRoute().getId());

            double wait_cost = bestWait;

            if (!s0.isEverBoarded() && !options.reverseOptimizing) {
                wait_cost *= options.waitAtBeginningFactor;
                s1.setInitialWaitTimeSeconds(bestWait);
            } else {
                wait_cost *= options.waitReluctance;
            }

            long preferences_penalty = options.preferencesPenaltyForRoute(getPattern().route);

            /* Compute penalty for non-preferred transfers. */
            int transferPenalty = 0;
            /* If this is not the first boarding, then we are transferring. */
            if (s0.isEverBoarded()) {
                TransferTable transferTable = options.getRoutingContext().transferTable;
                int transferTime = transferTable.getTransferTime(s0.getPreviousStop(),
                        getStop(), s0.getPreviousTrip(), trip, boarding);
                transferPenalty  = transferTable.determineTransferPenalty(transferTime,
                        options.nonpreferredTransferPenalty);
            }

            s1.incrementWeight(preferences_penalty + transferPenalty);

            // when reverse optimizing, the board cost needs to be applied on
            // alight to prevent state domination due to free alights
            if (options.reverseOptimizing) {
                s1.incrementWeight(wait_cost);
            } else {
                s1.incrementWeight(wait_cost + options.getBoardCost(s0.getNonTransitMode()));
            }

            s1.incrementWeight(getExtraWeight(options));

            // On-the-fly reverse optimization
            // determine if this needs to be reverse-optimized.
            // The last alight can be moved forward by bestWait (but no further) without
            // impacting the possibility of this trip
            if (options.reverseOptimizeOnTheFly && 
               !options.reverseOptimizing && 
                s0.isEverBoarded() && 
                s0.getLastNextArrivalDelta() <= bestWait &&
                s0.getLastNextArrivalDelta() > -1) {
                // it is re-reversed by optimize, so this still yields a forward tree
                State optimized = s1.makeState().optimizeOrReverse(true, true);
                if (optimized == null) LOG.error("Null optimized state. This shouldn't happen.");
                return optimized;
            }

            /* If we didn't return an optimized path, return an unoptimized one. */
            return s1.makeState();
        }
    }

    public long getExtraWeight(RoutingRequest options) {
        return 0;
    }

    public TripTimes getNextTrip(State s0, ServiceDay sd, Timetable timetable) {
        return timetable.getNextTrip(s0, sd, stopIndex, boarding);
    }

    public int calculateWait(State s0, ServiceDay sd, TripTimes tripTimes) {
        return boarding ?
                (int)(sd.time(tripTimes.getDepartureTime(stopIndex)) - s0.getTimeSeconds()):
                (int)(s0.getTimeSeconds() - sd.time(tripTimes.getArrivalTime(stopIndex)));
    }

    public boolean isDeviated() {
        return false;
    }

    /** @return the stop where this board/alight edge is located. */
    private Stop getStop() {
        PatternStopVertex stopVertex = (PatternStopVertex) (boarding ? tov : fromv);
        return stopVertex.getStop();
    }

    public State optimisticTraverse(State state0) {
        StateEditor s1 = state0.edit(this);
        // no cost (see patternalight)
        s1.setBackMode(getMode());
        return s1.makeState();
    }

    /* See weightLowerBound comment. */
    public double timeLowerBound(RoutingRequest options) {
        if ((options.arriveBy && boarding) || (!options.arriveBy && !boarding)) {
            if (!options.modes.get(modeMask)) {
                return Double.POSITIVE_INFINITY;
            }
            BitSet services = getPattern().services;
            for (ServiceDay sd : options.rctx.serviceDays) {
                if (sd.anyServiceRunning(services)) return 0;
            }
            return Double.POSITIVE_INFINITY;
        } else {
            return 0;
        }
    }

    /* If the main search is proceeding backward, the lower bound search is proceeding forward.
     * Check the mode or serviceIds of this pattern at board time to see whether this pattern is
     * worth exploring. If the main search is proceeding forward, board cost is added at board
     * edges. The lower bound search is proceeding backward, and if it has reached a board edge the
     * pattern was already deemed useful. */
    public double weightLowerBound(RoutingRequest options) {
        // return 0; // for testing/comparison, since 0 is always a valid heuristic value
        if ((options.arriveBy && boarding) || (!options.arriveBy && !boarding))
            return timeLowerBound(options);
        else
            return options.getBoardCostLowerBound();
    }

    @Override
    public int getStopIndex() {
        return stopIndex;
    }

    public String toString() {
        return "TransitBoardAlight(" +
                (boarding ? "boarding " : "alighting ") +
                getFromVertex() + " to " + getToVertex() + ")";
    }

}
