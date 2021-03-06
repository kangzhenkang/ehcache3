/*
 * Copyright Terracotta, Inc.
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
package org.ehcache.management.providers.statistics;

import org.ehcache.core.statistics.CacheOperationOutcomes;
import org.ehcache.core.statistics.TierOperationOutcomes;
import org.ehcache.management.ManagementRegistryServiceConfiguration;
import org.ehcache.management.config.StatisticsProviderConfiguration;
import org.ehcache.management.providers.CacheBinding;
import org.ehcache.management.providers.ExposedCacheBinding;
import org.terracotta.context.extended.OperationStatisticDescriptor;
import org.terracotta.context.extended.RegisteredStatistic;
import org.terracotta.context.extended.StatisticsRegistry;
import org.terracotta.management.model.capabilities.descriptors.Descriptor;
import org.terracotta.management.model.capabilities.descriptors.StatisticDescriptor;
import org.terracotta.management.model.stats.MemoryUnit;
import org.terracotta.management.model.stats.NumberUnit;
import org.terracotta.management.model.stats.Sample;
import org.terracotta.management.model.stats.Statistic;
import org.terracotta.management.model.stats.StatisticType;
import org.terracotta.management.model.stats.history.AverageHistory;
import org.terracotta.management.model.stats.history.CounterHistory;
import org.terracotta.management.model.stats.history.DurationHistory;
import org.terracotta.management.model.stats.history.RateHistory;
import org.terracotta.management.model.stats.history.RatioHistory;
import org.terracotta.management.model.stats.history.SizeHistory;
import org.terracotta.statistics.archive.Timestamped;
import org.terracotta.statistics.extended.SampleType;
import org.terracotta.statistics.extended.SampledStatistic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singleton;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.of;
import static org.terracotta.context.extended.ValueStatisticDescriptor.descriptor;

class StandardEhcacheStatistics extends ExposedCacheBinding {

  private static final Map<String, SampleType> COMPOUND_SUFFIXES = new HashMap<String, SampleType>();

  static {
    COMPOUND_SUFFIXES.put("Count", SampleType.COUNTER);
    COMPOUND_SUFFIXES.put("Rate", SampleType.RATE);
    COMPOUND_SUFFIXES.put("LatencyMinimum", SampleType.LATENCY_MIN);
    COMPOUND_SUFFIXES.put("LatencyMaximum", SampleType.LATENCY_MAX);
    COMPOUND_SUFFIXES.put("LatencyAverage", SampleType.LATENCY_AVG);
  }

  private final StatisticsRegistry statisticsRegistry;

  StandardEhcacheStatistics(ManagementRegistryServiceConfiguration registryConfiguration, CacheBinding cacheBinding, StatisticsProviderConfiguration statisticsProviderConfiguration, ScheduledExecutorService executor) {
    super(registryConfiguration, cacheBinding);
    this.statisticsRegistry = new StatisticsRegistry(cacheBinding.getCache(), executor, statisticsProviderConfiguration.averageWindowDuration(),
        statisticsProviderConfiguration.averageWindowUnit(), statisticsProviderConfiguration.historySize(), statisticsProviderConfiguration.historyInterval(), statisticsProviderConfiguration.historyIntervalUnit(),
        statisticsProviderConfiguration.timeToDisable(), statisticsProviderConfiguration.timeToDisableUnit());

    EnumSet<CacheOperationOutcomes.GetOutcome> hit = of(CacheOperationOutcomes.GetOutcome.HIT_NO_LOADER, CacheOperationOutcomes.GetOutcome.HIT_WITH_LOADER);
    EnumSet<CacheOperationOutcomes.GetOutcome> miss = of(CacheOperationOutcomes.GetOutcome.MISS_NO_LOADER, CacheOperationOutcomes.GetOutcome.MISS_WITH_LOADER);
    OperationStatisticDescriptor<CacheOperationOutcomes.GetOutcome> getCacheStatisticDescriptor = OperationStatisticDescriptor.descriptor("get", singleton("cache"), CacheOperationOutcomes.GetOutcome.class);

    statisticsRegistry.registerCompoundOperations("Cache:Hit", getCacheStatisticDescriptor, hit);
    statisticsRegistry.registerCompoundOperations("Cache:Miss", getCacheStatisticDescriptor, miss);
    statisticsRegistry.registerCompoundOperations("Cache:Clear", OperationStatisticDescriptor.descriptor("clear", singleton("cache"),CacheOperationOutcomes.ClearOutcome.class), allOf(CacheOperationOutcomes.ClearOutcome.class));
    statisticsRegistry.registerRatios("Cache:HitRatio", getCacheStatisticDescriptor, hit, allOf(CacheOperationOutcomes.GetOutcome.class));
    statisticsRegistry.registerRatios("Cache:MissRatio", getCacheStatisticDescriptor, miss, allOf(CacheOperationOutcomes.GetOutcome.class));

    Class<TierOperationOutcomes.GetOutcome> tierOperationGetOucomeClass = TierOperationOutcomes.GetOutcome.class;
    OperationStatisticDescriptor<TierOperationOutcomes.GetOutcome> getTierStatisticDescriptor = OperationStatisticDescriptor.descriptor("get", singleton("tier"), tierOperationGetOucomeClass);

    statisticsRegistry.registerCompoundOperations("Hit", getTierStatisticDescriptor, of(TierOperationOutcomes.GetOutcome.HIT));
    statisticsRegistry.registerCompoundOperations("Miss", getTierStatisticDescriptor, of(TierOperationOutcomes.GetOutcome.MISS));
    statisticsRegistry.registerCompoundOperations("Eviction",
        OperationStatisticDescriptor.descriptor("eviction", singleton("tier"),
        TierOperationOutcomes.EvictionOutcome.class),
        allOf(TierOperationOutcomes.EvictionOutcome.class));
    statisticsRegistry.registerRatios("HitRatio", getTierStatisticDescriptor, of(TierOperationOutcomes.GetOutcome.HIT),  allOf(tierOperationGetOucomeClass));
    statisticsRegistry.registerRatios("MissRatio", getTierStatisticDescriptor, of(TierOperationOutcomes.GetOutcome.MISS),  allOf(tierOperationGetOucomeClass));
    statisticsRegistry.registerCounter("MappingCount", descriptor("mappings", singleton("tier")));
    statisticsRegistry.registerCounter("MaxMappingCount", descriptor("maxMappings", singleton("tier")));
    statisticsRegistry.registerSize("AllocatedByteSize", descriptor("allocatedMemory", singleton("tier")));
    statisticsRegistry.registerSize("OccupiedByteSize", descriptor("occupiedMemory", singleton("tier")));
  }

  @SuppressWarnings("unchecked")
  Statistic<?, ?> queryStatistic(String statisticName, long since) {
    // first search for a non-compound stat
    SampledStatistic<? extends Number> statistic = statisticsRegistry.findSampledStatistic(statisticName);

    // if not found, it can be a compound stat, so search for it
    if (statistic == null) {
      for (Iterator<Entry<String, SampleType>> it = COMPOUND_SUFFIXES.entrySet().iterator(); it.hasNext() && statistic == null; ) {
        Entry<String, SampleType> entry = it.next();
        statistic = statisticsRegistry.findSampledCompoundStatistic(statisticName.substring(0, Math.max(0, statisticName.length() - entry.getKey().length())), entry.getValue());
      }
    }

    if (statistic != null) {
      List<? extends Timestamped<? extends Number>> history = statistic.history(since);
      List samples = new ArrayList<Sample<Number>>(history.size());
      for (Timestamped<? extends Number> timestamped : history) {
        Sample<Number> sample = new Sample<Number>(timestamped.getTimestamp(), timestamped.getSample());
        samples.add(sample);
      }

      switch (statistic.type()) {
        case COUNTER: return new CounterHistory((List<Sample<Long>>) samples, NumberUnit.COUNT);
        case RATE: return new RateHistory((List<Sample<Double>>) samples, TimeUnit.SECONDS);
        case LATENCY_MIN: return new DurationHistory((List<Sample<Long>>) samples, TimeUnit.NANOSECONDS);
        case LATENCY_MAX: return new DurationHistory((List<Sample<Long>>) samples, TimeUnit.NANOSECONDS);
        case LATENCY_AVG: return new AverageHistory((List<Sample<Double>>) samples, TimeUnit.NANOSECONDS);
        case RATIO: return new RatioHistory((List<Sample<Double>>) samples, NumberUnit.RATIO);
        case SIZE: return new SizeHistory((List<Sample<Long>>) samples, MemoryUnit.B);
        default: throw new UnsupportedOperationException(statistic.type().name());
      }
    }

    throw new IllegalArgumentException("No registered statistic named '" + statisticName + "'");
  }

  @Override
  public Collection<Descriptor> getDescriptors() {
    Set<Descriptor> capabilities = new HashSet<Descriptor>();
    Map<String, RegisteredStatistic> registrations = statisticsRegistry.getRegistrations();
    for (Entry<String, RegisteredStatistic> entry : registrations.entrySet()) {
      String statisticName = entry.getKey();
      RegisteredStatistic registeredStatistic = registrations.get(statisticName);
      switch (registeredStatistic.getType()) {
        case COUNTER:
          capabilities.add(new StatisticDescriptor(statisticName, StatisticType.COUNTER_HISTORY));
          break;
        case RATIO:
          capabilities.add(new StatisticDescriptor(entry.getKey() + "Ratio", StatisticType.RATIO_HISTORY));
          break;
        case SIZE:
          capabilities.add(new StatisticDescriptor(statisticName, StatisticType.SIZE_HISTORY));
          break;
        case COMPOUND:
          capabilities.add(new StatisticDescriptor(entry.getKey() + "Count", StatisticType.COUNTER_HISTORY));
          capabilities.add(new StatisticDescriptor(entry.getKey() + "Rate", StatisticType.RATE_HISTORY));
          capabilities.add(new StatisticDescriptor(entry.getKey() + "LatencyMinimum", StatisticType.DURATION_HISTORY));
          capabilities.add(new StatisticDescriptor(entry.getKey() + "LatencyMaximum", StatisticType.DURATION_HISTORY));
          capabilities.add(new StatisticDescriptor(entry.getKey() + "LatencyAverage", StatisticType.AVERAGE_HISTORY));
          break;
        default:
          throw new UnsupportedOperationException(registeredStatistic.getType().name());
      }
    }

    return capabilities;
  }

  void dispose() {
    statisticsRegistry.clearRegistrations();
  }


}
