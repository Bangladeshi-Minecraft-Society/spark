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

package me.lucko.spark.common.command.modules;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.command.sender.CommandSender;
import me.lucko.spark.common.command.tabcomplete.CompletionSupplier;
import me.lucko.spark.common.command.tabcomplete.TabCompleter;
import me.lucko.spark.common.sampler.jfr.MethodCallFrequencyProfiler;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.command.Arguments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

/**
 * Command module for method call frequency profiling using JFR.
 */
public class MethodCallFrequencyModule implements CommandModule {
    private final SparkPlatform platform;
    
    /** Active profiler instance */
    private MethodCallFrequencyProfiler profiler = null;
    
    public MethodCallFrequencyModule(SparkPlatform platform) {
        this.platform = platform;
    }
    
    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("jfrmethods", "methodcalls", "methodfreq")
                .argumentUsage("start", "", null)
                .argumentUsage("stop", "", null)
                .argumentUsage("filter", "add|remove|clear|list", null)
                .argumentUsage("threshold", "value", null)
                .argumentUsage("status", "", null)
                .executor(this::handleMethodCallFrequency)
                .tabCompleter((platform, sender, arguments) -> {
                    if (arguments.isEmpty()) {
                        return Arrays.asList("start", "stop", "filter", "threshold", "status");
                    }
                    
                    String subCommand = arguments.get(0).toLowerCase();
                    if (arguments.size() == 1) {
                        List<String> options = Arrays.asList("start", "stop", "filter", "threshold", "status");
                        return filterStartingWith(subCommand, options);
                    }
                    
                    if (subCommand.equals("start") && arguments.size() == 2) {
                        List<String> options = Arrays.asList("--rate=20", "--filter=", "--threshold=1000");
                        return filterStartingWith(arguments.get(1), options);
                    }
                    
                    if (subCommand.equals("filter") && arguments.size() == 2) {
                        List<String> options = Arrays.asList("add", "remove", "clear", "list");
                        return filterStartingWith(arguments.get(1), options);
                    }
                    
                    if (subCommand.equals("threshold") && arguments.size() == 2) {
                        return Collections.singletonList("1000");
                    }
                    
                    return Collections.emptyList();
                })
                .build()
        );
    }
    
    /**
     * Filter options that start with the given prefix (case-insensitive)
     */
    private List<String> filterStartingWith(String prefix, List<String> options) {
        if (prefix.isEmpty()) {
            return options;
        }
        
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) {
                result.add(option);
            }
        }
        return result;
    }
    
    private void handleMethodCallFrequency(SparkPlatform platform, CommandSender sender, CommandResponseHandler resp, Arguments arguments) {
        List<String> args = arguments.raw();
        if (args.isEmpty()) {
            resp.replyPrefixed(text("Usage: /spark jfrmethods <start|stop|filter|threshold|status>", YELLOW));
            return;
        }
        
        String subCommand = args.get(0).toLowerCase();
        if (subCommand.startsWith("--")) {
            resp.replyPrefixed(text("Usage: /spark jfrmethods <start|stop|filter|threshold|status>", YELLOW));
            return;
        }
        
        switch (subCommand) {
            case "start":
                startProfiler(args.size() > 1 ? args.subList(1, args.size()) : Collections.emptyList(), resp);
                break;
            case "stop":
                stopProfiler(resp);
                break;
            case "filter":
                manageFilters(args.size() > 1 ? args.subList(1, args.size()) : Collections.emptyList(), resp);
                break;
            case "threshold":
                setThreshold(args.size() > 1 ? args.subList(1, args.size()) : Collections.emptyList(), resp);
                break;
            case "status":
                showStatus(resp);
                break;
            default:
                resp.replyPrefixed(text("Unknown sub-command: " + args.get(0), RED));
                break;
        }
    }
    
    private void startProfiler(List<String> args, CommandResponseHandler resp) {
        if (profiler != null && profiler.isActive()) {
            resp.replyPrefixed(text("Method call frequency profiler is already running. Stop it first with /spark jfrmethods stop", YELLOW));
            return;
        }
        
        // Parse arguments
        int samplingRate = 20; // Default to 20ms
        List<Pattern> filters = new ArrayList<>();
        long threshold = 1000; // Default threshold
        
        for (String arg : args) {
            if (arg.startsWith("--rate=")) {
                try {
                    samplingRate = Integer.parseInt(arg.substring(7));
                    if (samplingRate < 1) {
                        resp.replyPrefixed(text("Sampling rate must be at least 1ms", RED));
                        return;
                    }
                } catch (NumberFormatException e) {
                    resp.replyPrefixed(text("Invalid sampling rate: " + arg.substring(7), RED));
                    return;
                }
            } else if (arg.startsWith("--filter=")) {
                String filterPattern = arg.substring(9);
                try {
                    filters.add(Pattern.compile(filterPattern));
                } catch (PatternSyntaxException e) {
                    resp.replyPrefixed(text("Invalid filter pattern: " + filterPattern, RED));
                    return;
                }
            } else if (arg.startsWith("--threshold=")) {
                try {
                    threshold = Long.parseLong(arg.substring(12));
                    if (threshold < 0) {
                        resp.replyPrefixed(text("Threshold cannot be negative", RED));
                        return;
                    }
                } catch (NumberFormatException e) {
                    resp.replyPrefixed(text("Invalid threshold: " + arg.substring(12), RED));
                    return;
                }
            }
        }
        
        // Get tick hook
        TickHook tickHook = platform.getTickHook();
        if (tickHook == null) {
            resp.replyPrefixed(text("Method call frequency profiling requires tick hook support, which is not available on this platform", RED));
            return;
        }
        
        // Create the profiler
        try {
            MethodCallFrequencyProfiler.Builder builder = new MethodCallFrequencyProfiler.Builder(platform)
                    .tickHook(tickHook)
                    .samplingRate(samplingRate)
                    .excessiveCallThreshold(threshold);
            
            // Add filters
            for (Pattern filter : filters) {
                builder.addMethodFilter(filter);
            }
            
            profiler = builder.build();
            profiler.start();
            
            resp.broadcastPrefixed(text("Started method call frequency profiler with sampling rate " + 
                    samplingRate + "ms and threshold " + threshold, GREEN));
        } catch (Exception e) {
            resp.replyPrefixed(text("Failed to start method call frequency profiler: " + e.getMessage(), RED));
            e.printStackTrace();
        }
    }
    
    private void stopProfiler(CommandResponseHandler resp) {
        if (profiler == null || !profiler.isActive()) {
            resp.replyPrefixed(text("Method call frequency profiler is not running", YELLOW));
            return;
        }
        
        try {
            profiler.close();
            resp.broadcastPrefixed(text("Stopped method call frequency profiler", GREEN));
            
            // Display a summary of the most frequent methods
            Map<String, Long> methodCounts = profiler.getMethodCallsForTick(
                    platform.getTickHook().getCurrentTick() - 1);
            
            if (methodCounts.isEmpty()) {
                resp.replyPrefixed(text("No method call data collected in the last tick", YELLOW));
            } else {
                // Sort methods by call count (descending)
                List<Map.Entry<String, Long>> sortedMethods = new ArrayList<>(methodCounts.entrySet());
                sortedMethods.sort(Map.Entry.<String, Long>comparingByValue().reversed());
                
                // Display top 10 methods
                resp.replyPrefixed(text("Top 10 methods by call frequency in the last tick:", GREEN));
                int count = 0;
                for (Map.Entry<String, Long> entry : sortedMethods) {
                    if (count++ >= 10) break;
                    
                    String trend = profiler.getMethodTrendDescription(entry.getKey());
                    resp.reply(text("  " + entry.getKey() + ": " + entry.getValue() + " calls (" + trend + ")", GRAY));
                }
                
                // Suggest viewing full results in web viewer
                resp.reply(text("Use the Spark web viewer to see detailed method call frequency data", YELLOW));
            }
            
            // Clear the reference
            profiler = null;
        } catch (Exception e) {
            resp.replyPrefixed(text("Error stopping method call frequency profiler: " + e.getMessage(), RED));
            e.printStackTrace();
        }
    }
    
    private void manageFilters(List<String> args, CommandResponseHandler resp) {
        if (profiler == null) {
            resp.replyPrefixed(text("Method call frequency profiler is not active", YELLOW));
            return;
        }
        
        if (args.isEmpty()) {
            resp.replyPrefixed(text("Usage: /spark jfrmethods filter <add|remove|clear|list>", YELLOW));
            return;
        }
        
        String action = args.get(0).toLowerCase();
        switch (action) {
            case "add":
                if (args.size() < 2) {
                    resp.replyPrefixed(text("Usage: /spark jfrmethods filter add <pattern>", YELLOW));
                    return;
                }
                
                try {
                    Pattern pattern = Pattern.compile(args.get(1));
                    profiler.addMethodFilter(pattern);
                    resp.replyPrefixed(text("Added method filter: " + args.get(1), GREEN));
                } catch (PatternSyntaxException e) {
                    resp.replyPrefixed(text("Invalid regex pattern: " + args.get(1), RED));
                }
                break;
            case "remove":
                if (args.size() < 2) {
                    resp.replyPrefixed(text("Usage: /spark jfrmethods filter remove <pattern>", YELLOW));
                    return;
                }
                
                try {
                    Pattern pattern = Pattern.compile(args.get(1));
                    profiler.removeMethodFilter(pattern);
                    resp.replyPrefixed(text("Removed method filter: " + args.get(1), GREEN));
                } catch (PatternSyntaxException e) {
                    resp.replyPrefixed(text("Invalid regex pattern: " + args.get(1), RED));
                }
                break;
            case "clear":
                profiler.clearMethodFilters();
                resp.replyPrefixed(text("Cleared all method filters", GREEN));
                break;
            case "list":
                // This is a placeholder as we don't have direct access to the filter patterns
                resp.replyPrefixed(text("Method filters are active but cannot be listed", YELLOW));
                break;
            default:
                resp.replyPrefixed(text("Unknown filter command: " + args.get(0), RED));
                break;
        }
    }
    
    private void setThreshold(List<String> args, CommandResponseHandler resp) {
        if (profiler == null) {
            resp.replyPrefixed(text("Method call frequency profiler is not active", YELLOW));
            return;
        }
        
        if (args.isEmpty()) {
            resp.replyPrefixed(text("Usage: /spark jfrmethods threshold <value>", YELLOW));
            return;
        }
        
        try {
            long threshold = Long.parseLong(args.get(0));
            if (threshold < 0) {
                resp.replyPrefixed(text("Threshold cannot be negative", RED));
                return;
            }
            
            // Ideally we would update the threshold, but the current implementation doesn't
            // allow changing it after construction. For now, we'll just inform the user.
            resp.replyPrefixed(text("Threshold cannot be changed while profiler is running. Please stop and restart with --threshold=" + threshold, YELLOW));
        } catch (NumberFormatException e) {
            resp.replyPrefixed(text("Invalid threshold: " + args.get(0), RED));
        }
    }
    
    private void showStatus(CommandResponseHandler resp) {
        if (profiler == null || !profiler.isActive()) {
            resp.replyPrefixed(text("Method call frequency profiler is not running", YELLOW));
            return;
        }
        
        resp.replyPrefixed(text("Method call frequency profiler is running", GREEN));
        
        // Get data for the current tick
        int currentTick = platform.getTickHook().getCurrentTick();
        Map<String, Long> methodCounts = profiler.getMethodCallsForTick(currentTick - 1);
        
        if (methodCounts.isEmpty()) {
            resp.reply(text("No method call data collected for the previous tick", GRAY));
        } else {
            resp.reply(text("Collected data for " + methodCounts.size() + " methods in the previous tick", GRAY));
            
            // Display top 5 methods
            List<Map.Entry<String, Long>> sortedMethods = new ArrayList<>(methodCounts.entrySet());
            sortedMethods.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            
            resp.reply(text("Top 5 methods by call frequency:", GRAY));
            int count = 0;
            for (Map.Entry<String, Long> entry : sortedMethods) {
                if (count++ >= 5) break;
                
                resp.reply(text("  " + entry.getKey() + ": " + entry.getValue() + " calls", GRAY));
            }
        }
    }
} 