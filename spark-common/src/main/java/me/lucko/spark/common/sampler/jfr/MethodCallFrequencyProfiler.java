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

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.tick.TickHook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;

/**
 * Implements function call frequency analysis using JFR (Java Flight Recorder).
 * Tracks method invocations per server tick with minimal performance impact.
 */
public class MethodCallFrequencyProfiler implements FlightRecorderListener, AutoCloseable {

    // Default settings
    private static final long DEFAULT_RECORDING_DELAY_MS = 1000;
    private static final int DEFAULT_SAMPLING_RATE = 20; // ms
    private static final double DEFAULT_OVERHEAD_TARGET = 0.01; // 1%
    private static final int HISTORY_WINDOW_SIZE = 1200; // Store 1 minute of tick data (at 20 TPS)
    
    /** The platform */
    private final SparkPlatform platform;
    
    /** The tick hook used to synchronize with server ticks */
    private final TickHook tickHook;
    
    /** Executor for background tasks */
    private final ScheduledExecutorService executor;
    
    /** The JFR recording */
    private Recording recording;
    
    /** Path where JFR data is temporarily stored */
    private Path recordingPath;
    
    /** Current tick being processed */
    private int currentTick = -1;
    
    /** Map of ticks to method call counts */
    private final Map<Integer, Map<String, Long>> tickMethodCounts = new ConcurrentHashMap<>();
    
    /** Map of method names to their trend status */
    private final Map<String, Integer> methodTrends = new ConcurrentHashMap<>();
    
    /** Set of method patterns to filter */
    private final Set<Pattern> methodFilters = ConcurrentHashMap.newKeySet();
    
    /** Flag indicating whether the profiler is active */
    private final AtomicBoolean active = new AtomicBoolean(false);
    
    /** The sampling interval in milliseconds */
    private final int samplingInterval;
    
    /** Configuration for the profiler */
    private final Configuration configuration;
    
    /** Threshold for alerting on excessive call frequencies */
    private final long excessiveCallThreshold;
    
    /**
     * Creates a new method call frequency profiler.
     *
     * @param platform the spark platform
     * @param tickHook the tick hook for synchronizing with server ticks
     * @param samplingRate the sampling rate in milliseconds (default: 20ms)
     * @param methodFilters patterns of methods to include (empty for all methods)
     * @param excessiveCallThreshold threshold for alerting on excessive call frequencies
     */
    public MethodCallFrequencyProfiler(SparkPlatform platform, TickHook tickHook, int samplingRate, 
                                       Set<Pattern> methodFilters, long excessiveCallThreshold) {
        this.platform = platform;
        this.tickHook = tickHook;
        this.samplingInterval = samplingRate > 0 ? samplingRate : DEFAULT_SAMPLING_RATE;
        this.excessiveCallThreshold = excessiveCallThreshold;
        
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "spark-jfr-method-profiler");
            thread.setDaemon(true);
            return thread;
        });
        
        if (methodFilters != null && !methodFilters.isEmpty()) {
            this.methodFilters.addAll(methodFilters);
        }
        
        // Initialize JFR configuration
        try {
            this.configuration = Configuration.getConfiguration("profile");
        } catch (IOException | IllegalArgumentException | java.text.ParseException e) {
            throw new RuntimeException("Failed to get JFR configuration", e);
        }
        
        // Register as JFR listener
        FlightRecorder.addListener(this);
    }
    
    /**
     * Starts the method call frequency profiler.
     */
    public void start() {
        if (!active.compareAndSet(false, true)) {
            return; // Already running
        }
        
        try {
            // Create temporary file for JFR data
            this.recordingPath = platform.getTemporaryFiles().create("spark-", "-method-profile.jfr");
            
            // Start the recording
            this.recording = new Recording(this.configuration);
            this.recording.setName("spark-method-profile");
            this.recording.setMaxAge(Duration.ofMinutes(10));
            this.recording.setDestination(this.recordingPath);
            
            // Configure method profiling - we'll use a basic configuration
            // that works across different JDK versions
            try {
                // Enable execution sampling with our specified interval
                recording.enable("jdk.ExecutionSample");
                
                // Other settings would be configured here if needed
                // This simplified approach works across JDK versions
            } catch (Exception e) {
                platform.getPlugin().log(Level.WARNING, "Unable to fully configure JFR settings", e);
            }
            
            // Start recording
            this.recording.start();
            
            // Initialize current tick
            this.currentTick = this.tickHook.getCurrentTick();
            
            // Schedule tick-based data processing
            this.executor.scheduleWithFixedDelay(
                this::processTickData,
                DEFAULT_RECORDING_DELAY_MS,
                50, // Process roughly every server tick (50ms)
                TimeUnit.MILLISECONDS
            );
            
            platform.getPlugin().log(Level.INFO, "Started JFR method call frequency profiler");
        } catch (Exception e) {
            active.set(false);
            platform.getPlugin().log(Level.SEVERE, "Failed to start JFR method call frequency profiler", e);
            throw new RuntimeException("Failed to start JFR profiler", e);
        }
    }
    
    /**
     * Stops the method call frequency profiler.
     */
    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return; // Not running
        }
        
        // Stop the recording
        if (recording != null) {
            recording.stop();
            recording.close();
        }
        
        // Clean up temporary file
        try {
            if (recordingPath != null) {
                Files.deleteIfExists(recordingPath);
            }
        } catch (IOException e) {
            platform.getPlugin().log(Level.WARNING, "Failed to delete JFR recording file", e);
        }
        
        // Shutdown executor
        executor.shutdown();
        
        // Unregister as JFR listener
        FlightRecorder.removeListener(this);
        
        platform.getPlugin().log(Level.INFO, "Stopped JFR method call frequency profiler");
    }
    
    /**
     * Process method call data for the current tick.
     */
    private void processTickData() {
        if (!active.get() || recording == null) {
            return;
        }
        
        int newTick = tickHook.getCurrentTick();
        if (newTick == currentTick) {
            return; // Still in the same tick
        }
        
        try {
            // Read JFR data since last check
            Map<String, Long> methodCounts = new HashMap<>();
            
            // Process the JFR data using our specialized reader
            List<Pattern> filterPatterns = new ArrayList<>(methodFilters);
            try (JfrMethodReader reader = new JfrMethodReader(recordingPath, filterPatterns)) {
                methodCounts = reader.processMethodCalls();
            }
            
            // Only store if we have data
            if (!methodCounts.isEmpty()) {
                // Check for excessive call frequencies
                checkExcessiveCallFrequencies(methodCounts);
                
                // Update method trends
                updateMethodTrends(currentTick, methodCounts);
                
                // Store method counts for this tick
                tickMethodCounts.put(currentTick, Collections.unmodifiableMap(methodCounts));
                
                // Keep only the history window size worth of data
                if (tickMethodCounts.size() > HISTORY_WINDOW_SIZE) {
                    tickMethodCounts.keySet().stream()
                        .sorted()
                        .limit(tickMethodCounts.size() - HISTORY_WINDOW_SIZE)
                        .forEach(tickMethodCounts::remove);
                }
            }
            
            // Update current tick
            currentTick = newTick;
        } catch (Exception e) {
            platform.getPlugin().log(Level.WARNING, "Error processing JFR method call data", e);
        }
    }
    
    /**
     * Checks for methods with excessive call frequencies and logs warnings.
     *
     * @param methodCounts the method call counts for the current tick
     */
    private void checkExcessiveCallFrequencies(Map<String, Long> methodCounts) {
        if (excessiveCallThreshold <= 0) {
            return; // Feature disabled
        }
        
        for (Map.Entry<String, Long> entry : methodCounts.entrySet()) {
            if (entry.getValue() > excessiveCallThreshold) {
                platform.getPlugin().log(Level.WARNING, 
                    "Excessive method calls detected: " + entry.getKey() + 
                    " was called " + entry.getValue() + " times in tick " + currentTick);
            }
        }
    }
    
    /**
     * Updates trend analysis for methods based on historical data.
     *
     * @param tick the current tick
     * @param methodCounts the new method counts
     */
    private void updateMethodTrends(int tick, Map<String, Long> methodCounts) {
        // Get the previous tick's data for comparison
        int previousTick = tick - 1;
        Map<String, Long> previousCounts = tickMethodCounts.get(previousTick);
        
        // If we have previous data, calculate trends
        if (previousCounts != null) {
            for (Map.Entry<String, Long> entry : methodCounts.entrySet()) {
                String methodName = entry.getKey();
                long currentCount = entry.getValue();
                Long previousCount = previousCounts.get(methodName);
                
                if (previousCount != null) {
                    // Determine trend
                    int trend;
                    if (currentCount > previousCount) {
                        trend = 1; // Increasing
                    } else if (currentCount < previousCount) {
                        trend = -1; // Decreasing
                    } else {
                        trend = 0; // Stable
                    }
                    
                    // Update trend
                    methodTrends.put(methodName, trend);
                }
            }
        }
    }
    
    /**
     * Gets the method call counts for a specific tick.
     *
     * @param tick the tick number
     * @return map of method names to call counts, or empty map if not available
     */
    public Map<String, Long> getMethodCallsForTick(int tick) {
        return tickMethodCounts.getOrDefault(tick, Collections.emptyMap());
    }
    
    /**
     * Gets method call counts for a range of ticks.
     *
     * @param startTick the start tick (inclusive)
     * @param endTick the end tick (inclusive)
     * @return map of ticks to method call counts
     */
    public Map<Integer, Map<String, Long>> getMethodCallsForTickRange(int startTick, int endTick) {
        Map<Integer, Map<String, Long>> result = new HashMap<>();
        
        for (int tick = startTick; tick <= endTick; tick++) {
            Map<String, Long> counts = tickMethodCounts.get(tick);
            if (counts != null) {
                result.put(tick, counts);
            }
        }
        
        return result;
    }
    
    /**
     * Gets the trend for a specific method.
     *
     * @param methodName the method name
     * @return trend value (1=increasing, 0=stable, -1=decreasing, null if unknown)
     */
    public Integer getMethodTrend(String methodName) {
        return methodTrends.get(methodName);
    }
    
    /**
     * Gets a user-friendly trend description for a method.
     *
     * @param methodName the method name
     * @return string describing the trend, or "unknown" if not available
     */
    public String getMethodTrendDescription(String methodName) {
        Integer trend = methodTrends.get(methodName);
        if (trend == null) {
            return "unknown";
        }
        
        return switch (trend) {
            case 1 -> "increasing";
            case -1 -> "decreasing";
            default -> "stable";
        };
    }
    
    /**
     * Adds a method filter pattern.
     *
     * @param pattern the regex pattern to match method names
     */
    public void addMethodFilter(Pattern pattern) {
        if (pattern != null) {
            methodFilters.add(pattern);
        }
    }
    
    /**
     * Removes a method filter pattern.
     *
     * @param pattern the pattern to remove
     */
    public void removeMethodFilter(Pattern pattern) {
        if (pattern != null) {
            methodFilters.remove(pattern);
        }
    }
    
    /**
     * Clears all method filters.
     */
    public void clearMethodFilters() {
        methodFilters.clear();
    }
    
    /**
     * Returns if the profiler is currently active.
     *
     * @return true if active
     */
    public boolean isActive() {
        return active.get();
    }
    
    /**
     * JFR notification listener.
     */
    @Override
    public void recordingStateChanged(Recording recording) {
        // Monitor recording state changes if needed
    }
    
    /**
     * Builder for creating a MethodCallFrequencyProfiler.
     */
    public static class Builder {
        private final SparkPlatform platform;
        private TickHook tickHook;
        private int samplingRate = DEFAULT_SAMPLING_RATE;
        private final Set<Pattern> methodFilters = ConcurrentHashMap.newKeySet();
        private long excessiveCallThreshold = 1000; // Default threshold
        
        public Builder(SparkPlatform platform) {
            this.platform = platform;
        }
        
        public Builder tickHook(TickHook tickHook) {
            this.tickHook = tickHook;
            return this;
        }
        
        public Builder samplingRate(int rateMs) {
            this.samplingRate = rateMs;
            return this;
        }
        
        public Builder addMethodFilter(String regex) {
            if (regex != null && !regex.isEmpty()) {
                this.methodFilters.add(Pattern.compile(regex));
            }
            return this;
        }
        
        public Builder addMethodFilter(Pattern pattern) {
            if (pattern != null) {
                this.methodFilters.add(pattern);
            }
            return this;
        }
        
        public Builder excessiveCallThreshold(long threshold) {
            this.excessiveCallThreshold = threshold;
            return this;
        }
        
        public MethodCallFrequencyProfiler build() {
            if (tickHook == null) {
                tickHook = platform.getTickHook();
                if (tickHook == null) {
                    throw new IllegalStateException("Tick hook is required for method call frequency profiling");
                }
            }
            
            return new MethodCallFrequencyProfiler(platform, tickHook, samplingRate, methodFilters, excessiveCallThreshold);
        }
    }
} 