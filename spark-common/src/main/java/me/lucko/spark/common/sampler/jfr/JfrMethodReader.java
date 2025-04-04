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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A specialized JFR reader for processing method call events.
 * Extends the base JfrReader with method-specific functionality.
 */
public class JfrMethodReader extends JfrReader {

    private static final String JDK_METHOD_ENTRY_EVENT = "jdk.ExecutionSample";
    private static final String JDK_METHOD_SAMPLE_EVENT = "jdk.MethodSample";
    
    /** Pattern to filter method names */
    private final List<Pattern> methodFilters;
    
    /** Map of method name to the aggregated method event */
    private final Map<String, JfrMethodEvent> methodEvents = new ConcurrentHashMap<>();
    
    /**
     * Creates a new JFR method reader.
     *
     * @param path the path to the JFR recording file
     * @param methodFilters regex patterns to filter method names (empty for all methods)
     * @throws IOException if an I/O error occurs
     */
    public JfrMethodReader(Path path, List<Pattern> methodFilters) throws IOException {
        super(path);
        this.methodFilters = methodFilters != null ? methodFilters : new ArrayList<>();
    }
    
    /**
     * Processes the JFR recording and collects method call frequency data.
     *
     * @return a map of method names to call counts
     * @throws IOException if an I/O error occurs
     */
    public Map<String, Long> processMethodCalls() throws IOException {
        Map<String, Long> results = new HashMap<>();
        
        // Process both execution samples and method samples
        processEventType(JDK_METHOD_ENTRY_EVENT);
        processEventType(JDK_METHOD_SAMPLE_EVENT);
        
        // Convert to the result format
        for (JfrMethodEvent event : methodEvents.values()) {
            if (event.getCallCount() > 0) {
                results.put(event.getMethodName(), event.getCallCount());
            }
        }
        
        return results;
    }
    
    /**
     * Process events of a specific type.
     *
     * @param eventType the JFR event type name
     * @throws IOException if an I/O error occurs
     */
    private void processEventType(String eventType) throws IOException {
        // Implementation note: The full implementation would parse the JFR data structure
        // to extract method call information. This simplified version demonstrates
        // the structure and approach.
        
        // In a complete implementation, we would:
        // 1. Get events of the specified type
        // 2. Extract method name, thread ID, and timestamp
        // 3. Aggregate call counts by method name
        // 4. Apply method filters if any are defined
        
        // Simplified implementation:
        // For now, we'll just set up the structure and leverage
        // JFR's sampling capabilities to approximate call frequencies
    }
    
    /**
     * Adds a method event to the collection.
     *
     * @param methodName the method name
     * @param callCount the call count to add
     * @param timestamp the timestamp
     * @param threadId the thread ID
     * @param stackDepth the stack depth
     */
    private void addMethodEvent(String methodName, long callCount, long timestamp, int threadId, int stackDepth) {
        // Skip if the method doesn't match our filters
        if (!matchesFilters(methodName)) {
            return;
        }
        
        methodEvents.compute(methodName, (name, existing) -> {
            if (existing == null) {
                return new JfrMethodEvent(name, callCount, timestamp, threadId, stackDepth);
            } else {
                existing.incrementCallCount(callCount);
                return existing;
            }
        });
    }
    
    /**
     * Checks if a method name matches any of the filters.
     * If no filters are defined, all methods match.
     *
     * @param methodName the method name to check
     * @return true if the method name matches a filter (or if no filters are defined)
     */
    private boolean matchesFilters(String methodName) {
        if (methodFilters.isEmpty()) {
            return true;
        }
        
        for (Pattern pattern : methodFilters) {
            if (pattern.matcher(methodName).matches()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Gets the aggregated method events.
     *
     * @return a map of method name to method event
     */
    public Map<String, JfrMethodEvent> getMethodEvents() {
        return methodEvents;
    }
} 