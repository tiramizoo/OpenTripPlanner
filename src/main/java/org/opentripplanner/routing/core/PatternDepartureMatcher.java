package org.opentripplanner.routing.core;

import org.opentripplanner.routing.trippattern.TripTimes;

import java.io.Serializable;

public interface PatternDepartureMatcher extends Cloneable {

    boolean matches(ServiceDay serviceDay, TripTimes tripTimes, int stopIndex);

    static PatternDepartureMatcher emptyMatcher() {
        return new PatternDepartureMatcher() {
            @Override
            public boolean matches(ServiceDay serviceDay, TripTimes tripTimes, int stopIndex) {
                return false;
            }

            @Override
            public PatternDepartureMatcher clone() {
                try {
                    return (PatternDepartureMatcher) super.clone();
                } catch (CloneNotSupportedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    PatternDepartureMatcher clone();
}
