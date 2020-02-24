package org.opentripplanner.routing.core;

import org.opentripplanner.routing.trippattern.TripTimes;

public interface PatternDepartureMatcher {

    boolean matches(TripTimes tripTimes, int stopIndex);

    static PatternDepartureMatcher emptyMatcher() {
        return (tripTimes, stopIndex) -> false;
    }
}
