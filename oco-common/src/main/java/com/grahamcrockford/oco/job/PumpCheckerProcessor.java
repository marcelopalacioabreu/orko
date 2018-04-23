package com.grahamcrockford.oco.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedList;

import org.knowm.xchange.dto.marketdata.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.grahamcrockford.oco.spi.JobControl;
import com.grahamcrockford.oco.spi.TickerSpec;
import com.grahamcrockford.oco.telegram.TelegramService;
import com.grahamcrockford.oco.ticker.ExchangeEventRegistry;

import one.util.streamex.StreamEx;

class PumpCheckerProcessor implements PumpChecker.Processor {

  private static final BigDecimal TARGET = new BigDecimal("0.5");
  private static final Logger LOGGER = LoggerFactory.getLogger(PumpCheckerProcessor.class);
  private static final ColumnLogger COLUMN_LOGGER = new ColumnLogger(LOGGER,
    LogColumn.builder().name("#").width(24).rightAligned(false),
    LogColumn.builder().name("Exchange").width(12).rightAligned(false),
    LogColumn.builder().name("Pair").width(10).rightAligned(false),
    LogColumn.builder().name("Operation").width(13).rightAligned(false),
    LogColumn.builder().name("3 tick Mvmt %").width(13).rightAligned(true)
  );

  private final TelegramService telegramService;
  private final ExchangeEventRegistry exchangeEventRegistry;
  private final PumpChecker job;
  private final JobControl jobControl;

  @AssistedInject
  public PumpCheckerProcessor(@Assisted PumpChecker job,
                              @Assisted JobControl jobControl,
                              TelegramService telegramService,
                              ExchangeEventRegistry exchangeEventRegistry) {
    this.job = job;
    this.jobControl = jobControl;
    this.telegramService = telegramService;
    this.exchangeEventRegistry = exchangeEventRegistry;
  }

  @Override
  public boolean start() {
    exchangeEventRegistry.registerTicker(job.tickTrigger(), job.id(), this::tick);
    return true;
  }

  @Override
  public void stop() {
    exchangeEventRegistry.unregisterTicker(job.tickTrigger(), job.id());
  }

  private void tick(TickerSpec spec, Ticker ticker) {
    final TickerSpec ex = job.tickTrigger();

    BigDecimal asPercentage = BigDecimal.ZERO;
    LinkedList<BigDecimal> linkedList = new LinkedList<>(job.priceHistory());

    LOGGER.debug("Current price history: {}", linkedList);

    linkedList.add(ticker.getLast());
    if (linkedList.size() > 3) {
      linkedList.remove();

      BigDecimal movement = linkedList.getLast().subtract(linkedList.getFirst());

      LOGGER.debug("Movement: {}", movement);

      asPercentage = new BigDecimal(movement.doubleValue() * 100 / linkedList.getFirst().doubleValue()).setScale(5, RoundingMode.HALF_UP);

      LOGGER.debug("As %: {}", asPercentage);

      if (asPercentage.compareTo(TARGET) > 0) {
        if (!StreamEx.of(linkedList)
              .pairMap((a, b) -> a.compareTo(b) > 0)
              .has(true)) {
          String message = String.format(
              "Job [%s] on [%s/%s/%s] detected %s%% pump",
              job.id(),
              ex.exchange(),
              ex.base(),
              ex.counter(),
              asPercentage
            );
          LOGGER.info(message);
          telegramService.sendMessage(message);
          linkedList.clear();
        };
      } else if (asPercentage.compareTo(TARGET.negate()) < 0) {
        if (!StreamEx.of(linkedList)
            .pairMap((a, b) -> a.compareTo(b) < 0)
            .has(true)) {
          String message = String.format(
              "Job [%s] on [%s/%s/%s] detected %s%% dump",
              job.id(),
              ex.exchange(),
              ex.base(),
              ex.counter(),
              asPercentage
            );
          LOGGER.info(message);
          telegramService.sendMessage(message);
          linkedList.clear();
        };
      }
    }

    LOGGER.debug("New price history: {}", linkedList);

    COLUMN_LOGGER.line(
        job.id(),
        ex.exchange(),
        ex.pairName(),
        "Pump checker",
        asPercentage
      );

    jobControl.replace(job.toBuilder().priceHistory(linkedList).build());
  }

  public static final class Module extends AbstractModule {
    @Override
    protected void configure() {
      install(new FactoryModuleBuilder()
          .implement(PumpChecker.Processor.class, PumpCheckerProcessor.class)
          .build(PumpChecker.Processor.Factory.class));
    }
  }
}