package org.opentripplanner.routing.core;

import org.opentripplanner.routing.trippattern.TripTimes;

public interface PatternDepartureMatcher {

    boolean matches(ServiceDay serviceDay, TripTimes tripTimes, int stopIndex);

    static PatternDepartureMatcher emptyMatcher() {
        return (serviceDay, tripTimes, stopIndex) -> false;
    }
}
