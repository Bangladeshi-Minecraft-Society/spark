/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.sampler.jfr;

import me.lucko.spark.common.sampler.async.jfr.JfrReader;

import java.util.Objects;

/**
 * Represents a method event read from JFR data for function call frequency analysis.
 */
public class JfrMethodEvent {
    
    /** Method name in the format "package.class.method" */
    private final String methodName;
    
    /** Method call count within current tick */
    private long callCount;
    
    /** Timestamp of most recent invocation */
    private final long timestamp;
    
    /** Thread ID where method was called */
    private final int threadId;
    
    /** Call stack depth */
    private final int stackDepth;
    
    /** Trend analysis (1=increasing, 0=stable, -1=decreasing) */
    private int trend;
    
    /** Previous tick call count for trend analysis */
    private long previousTickCount;
    
    /**
     * Creates a new JFR method event.
     *
     * @param methodName the fully qualified method name
     * @param callCount initial call count
     * @param timestamp timestamp of the event
     * @param threadId thread ID where method was called
     * @param stackDepth the stack depth of the call
     */
    public JfrMethodEvent(String methodName, long callCount, long timestamp, int threadId, int stackDepth) {
        this.methodName = methodName;
        this.callCount = callCount;
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.stackDepth = stackDepth;
        this.trend = 0; // Initially stable
    }
    
    /**
     * Gets the method name.
     *
     * @return the method name
     */
    public String getMethodName() {
        return methodName;
    }
    
    /**
     * Gets the call count.
     *
     * @return the call count
     */
    public long getCallCount() {
        return callCount;
    }
    
    /**
     * Increments the call count.
     */
    public void incrementCallCount() {
        callCount++;
    }
    
    /**
     * Increments the call count by the specified amount.
     *
     * @param count the amount to increment by
     */
    public void incrementCallCount(long count) {
        callCount += count;
    }
    
    /**
     * Gets the timestamp.
     *
     * @return the timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets the thread ID.
     *
     * @return the thread ID
     */
    public int getThreadId() {
        return threadId;
    }
    
    /**
     * Gets the stack depth.
     *
     * @return the stack depth
     */
    public int getStackDepth() {
        return stackDepth;
    }
    
    /**
     * Updates the trend analysis based on the previous tick count.
     * 
     * @param newTickCount the new tick count
     */
    public void updateTrend(long newTickCount) {
        // Set trend based on comparison with previous count
        if (newTickCount > previousTickCount) {
            trend = 1; // Increasing
        } else if (newTickCount < previousTickCount) {
            trend = -1; // Decreasing
        } else {
            trend = 0; // Stable
        }
        
        // Update previous tick count for next comparison
        previousTickCount = newTickCount;
    }
    
    /**
     * Gets the trend.
     * 
     * @return 1 for increasing, 0 for stable, -1 for decreasing
     */
    public int getTrend() {
        return trend;
    }
    
    /**
     * Gets a user-friendly trend description.
     * 
     * @return string describing the trend
     */
    public String getTrendDescription() {
        switch (trend) {
            case 1:
                return "increasing";
            case -1:
                return "decreasing";
            default:
                return "stable";
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JfrMethodEvent that = (JfrMethodEvent) o;
        return threadId == that.threadId && 
               Objects.equals(methodName, that.methodName);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(methodName, threadId);
    }
    
    @Override
    public String toString() {
        return "JfrMethodEvent{" +
                "methodName='" + methodName + '\'' +
                ", callCount=" + callCount +
                ", timestamp=" + timestamp +
                ", threadId=" + threadId +
                ", trend=" + getTrendDescription() +
                '}';
    }
} 