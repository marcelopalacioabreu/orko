package com.grahamcrockford.oco.marketdata;

import static com.grahamcrockford.oco.marketdata.MarketDataType.ORDERBOOK;
import static com.grahamcrockford.oco.marketdata.MarketDataType.TICKER;
import static com.grahamcrockford.oco.marketdata.MarketDataType.TRADES;
import static java.util.Collections.emptySet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicReference;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.meta.RateLimit;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.TradeHistoryParamLimit;
import org.knowm.xchange.service.trade.params.TradeHistoryParamPaging;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.orders.DefaultOpenOrdersParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.grahamcrockford.oco.OcoConfiguration;
import com.grahamcrockford.oco.exchange.AccountServiceFactory;
import com.grahamcrockford.oco.exchange.ExchangeService;
import com.grahamcrockford.oco.exchange.TradeServiceFactory;
import com.grahamcrockford.oco.spi.TickerSpec;
import com.grahamcrockford.oco.util.SafelyDispose;

import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.ProductSubscription.ProductSubscriptionBuilder;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.disposables.Disposable;

/**
 * Maintains subscriptions to multiple exchanges' market data, using web sockets where it can
 * and polling where it can't, but this is abstracted away. All clients have access to reactive
 * streams of data to which they are subscribed.
 */
@Singleton
@VisibleForTesting
public class MarketDataSubscriptionManager extends AbstractExecutionThreadService {

  private static final int MAX_TRADES = 20;
  private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataSubscriptionManager.class);
  private static final int ORDERBOOK_DEPTH = 20;
  private static final Set<MarketDataType> STREAMING_MARKET_DATA = ImmutableSet.of(TICKER, TRADES, ORDERBOOK);

  private final ExchangeService exchangeService;
  private final TradeServiceFactory tradeServiceFactory;
  private final AccountServiceFactory accountServiceFactory;
  private final OcoConfiguration configuration;

  private final Map<String, AtomicReference<Set<MarketDataSubscription>>> nextSubscriptions;
  private final ConcurrentMap<String, Set<MarketDataSubscription>> subscriptionsPerExchange = Maps.newConcurrentMap();
  private final ConcurrentMap<String, Set<MarketDataSubscription>> pollsPerExchange = Maps.newConcurrentMap();
  private final Multimap<String, Disposable> disposablesPerExchange = HashMultimap.create();

  private final Flowable<TickerEvent> tickers;
  private final AtomicReference<FlowableEmitter<TickerEvent>> tickerEmitter = new AtomicReference<>();
  private final Flowable<OpenOrdersEvent> openOrders;
  private final AtomicReference<FlowableEmitter<OpenOrdersEvent>> openOrdersEmitter = new AtomicReference<>();
  private final Flowable<OrderBookEvent> orderbook;
  private final AtomicReference<FlowableEmitter<OrderBookEvent>> orderBookEmitter = new AtomicReference<>();
  private final Flowable<TradeEvent> trades;
  private final AtomicReference<FlowableEmitter<TradeEvent>> tradesEmitter = new AtomicReference<>();
  private final Flowable<TradeHistoryEvent> tradeHistory;
  private final AtomicReference<FlowableEmitter<TradeHistoryEvent>> tradeHistoryEmitter = new AtomicReference<>();
  private final Flowable<BalanceEvent> balance;
  private final AtomicReference<FlowableEmitter<BalanceEvent>> balanceEmitter = new AtomicReference<>();

  private final ConcurrentMap<TickerSpec, TickerEvent> latestTickers = Maps.newConcurrentMap();
  private final ConcurrentMap<TickerSpec, OrderBookEvent> latestOrderbooks = Maps.newConcurrentMap();


  @Inject
  @VisibleForTesting
  public MarketDataSubscriptionManager(ExchangeService exchangeService, OcoConfiguration configuration, TradeServiceFactory tradeServiceFactory, AccountServiceFactory accountServiceFactory) {
    this.exchangeService = exchangeService;
    this.configuration = configuration;
    this.tradeServiceFactory = tradeServiceFactory;
    this.accountServiceFactory = accountServiceFactory;

    this.nextSubscriptions = FluentIterable.from(exchangeService.getExchanges())
        .toMap(e -> new AtomicReference<Set<MarketDataSubscription>>());

    exchangeService.getExchanges().forEach(e -> {
      subscriptionsPerExchange.put(e, ImmutableSet.of());
      pollsPerExchange.put(e, ImmutableSet.of());
    });

    // Add every ticker as it arrives to our cache
    this.tickers = Flowable.create((FlowableEmitter<TickerEvent> e) -> tickerEmitter.set(e.serialize()), BackpressureStrategy.MISSING)
        .doOnNext(e -> latestTickers.put(e.spec(), e))
        .share()
        .onBackpressureLatest();

    this.openOrders = Flowable.create((FlowableEmitter<OpenOrdersEvent> e) -> openOrdersEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();

    // Add every copy of the order book as it arrives to our cache
    this.orderbook = Flowable.create((FlowableEmitter<OrderBookEvent> e) -> orderBookEmitter.set(e.serialize()), BackpressureStrategy.MISSING)
        .doOnNext(e -> latestOrderbooks.put(e.spec(), e))
        .share()
        .onBackpressureLatest();

    this.trades = Flowable.create((FlowableEmitter<TradeEvent> e) -> tradesEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
    this.tradeHistory = Flowable.create((FlowableEmitter<TradeHistoryEvent> e) -> tradeHistoryEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
    this.balance = Flowable.create((FlowableEmitter<BalanceEvent> e) -> balanceEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
  }


  /**
   * Updates the subscriptions for the specified exchanges on the next loop
   * tick. The delay is to avoid a large number of new subscriptions in quick
   * succession causing rate bans on exchanges. Call with an empty set to cancel
   * all subscriptions. None of the streams (e.g. {@link #getTicker(TickerSpec)}
   * will return anything until this is called, but there is no strict order in
   * which they need to be called.
   *
   * @param byExchange The exchanges and subscriptions for each.
   */
  public void updateSubscriptions(Set<MarketDataSubscription> subscriptions) {

    // Queue them up for each exchange's processing thread individually
    ImmutableListMultimap<String, MarketDataSubscription> byExchange = Multimaps.index(subscriptions, s -> s.spec().exchange());
    for (String exchangeName : exchangeService.getExchanges()) {
      nextSubscriptions.get(exchangeName).set(ImmutableSet.copyOf(byExchange.get(exchangeName)));
    }
  }


  /**
   * Gets the stream of a subscription.  Typed by the caller in
   * an unsafe manner for convenience.
   *
   * @param sub The subscription
   * @return The stream.
   */
  @SuppressWarnings("unchecked")
  public <T> Flowable<T> getSubscription(MarketDataSubscription sub) {
    switch (sub.type()) {
      case OPEN_ORDERS:
        return (Flowable<T>) getOpenOrders(sub.spec());
      case ORDERBOOK:
        return (Flowable<T>) getOrderBook(sub.spec());
      case TICKER:
        return (Flowable<T>) getTicker(sub.spec());
      case TRADES:
        return (Flowable<T>) getTrades(sub.spec());
      case USER_TRADE_HISTORY:
        return (Flowable<T>) getTradeHistory(sub.spec());
      case BALANCE:
        return (Flowable<T>) getBalance(sub.spec());
      default:
        throw new IllegalArgumentException("Unknown market data type");
    }
  }


  /**
   * Gets a stream of tickers, starting with any cached tickers.
   *
   * @param spec The ticker specification.
   * @return The ticker stream.
   */
  public Flowable<TickerEvent> getTicker(TickerSpec spec) {
    return tickers
      .startWith(Flowable.defer(() -> Flowable.fromIterable(latestTickers.values())))
      .filter(t -> t.spec().equals(spec))
      .doOnNext(t -> {
        if (LOGGER.isDebugEnabled()) logTicker("filtered", t);
      });
  }


  /**
   * Gets a stream of open order lists.
   *
   * @param spec The ticker specification.
   */
  public Flowable<OpenOrdersEvent> getOpenOrders(TickerSpec spec) {
    return openOrders.filter(t -> t.spec().equals(spec));
  }


  /**
   * Gets a stream containing updates to the order book.
   *
   * @param spec The ticker specification.
   */
  public Flowable<OrderBookEvent> getOrderBook(TickerSpec spec) {
    return orderbook
      .startWith(Flowable.defer(() -> Flowable.fromIterable(latestOrderbooks.values())))
      .filter(t -> t.spec().equals(spec));
  }


  /**
   * Gets a stream of trades.
   *
   * @param spec The ticker specification.
   */
  public Flowable<TradeEvent> getTrades(TickerSpec spec) {
    return trades.filter(t -> t.spec().equals(spec));
  }


  /**
   * Gets a stream with updates to the recent trade history.
   *
   * @param spec The ticker specification.
   */
  public Flowable<TradeHistoryEvent> getTradeHistory(TickerSpec spec) {
    return tradeHistory.filter(t -> t.spec().equals(spec));
  }


  /**
   * Gets a stream with updates to the balance.
   *
   * @param spec The ticker specification.
   */
  public Flowable<BalanceEvent> getBalance(TickerSpec spec) {
    return balance.filter(t -> t.exchange().equals(spec.exchange()) &&
        (t.balance().currency().equals(spec.base()) || t.balance().currency().equals(spec.counter())));
  }


  /**
   * Actually performs the subscription changes. Occurs synchronously in the
   * poll loop.
   */
  private void doSubscriptionChanges(String exchangeName) {
    Set<MarketDataSubscription> subscriptions = nextSubscriptions.get(exchangeName).getAndSet(null);
    try {
      if (subscriptions == null)
        return;

      LOGGER.debug("Updating {} subscriptions to: {}", exchangeName, subscriptions);

      // Remember all our old subscriptions for a moment...
      Set<MarketDataSubscription> oldSubscriptions = FluentIterable.from(Iterables.concat(
          subscriptionsPerExchange.get(exchangeName),
          pollsPerExchange.get(exchangeName)
        ))
        .toSet();

      // Disconnect any streaming exchanges where the tickers currently
      // subscribed mismatch the ones we want.
      if (!subscriptions.equals(oldSubscriptions)) {

        if (!oldSubscriptions.isEmpty()) {
          disconnectExchange(exchangeName);
        }

        // Clear cached tickers and order books for anything we've unsubscribed so that we don't feed out-of-date data
        Sets.difference(oldSubscriptions, subscriptions)
          .forEach(s -> {
            latestTickers.remove(s.spec());
            latestOrderbooks.remove(s.spec());
          });

        // Add new subscriptions if we have any
        if (!subscriptions.isEmpty()) {
          subscribe(exchangeName, subscriptions);
        }

      }

    } catch (Throwable t) {
      LOGGER.error("Error updating subscriptions", t);
      nextSubscriptions.get(exchangeName).compareAndSet(null, subscriptions);
    }
  }

  private void disconnectExchange(String exchangeName) {
    Exchange exchange = exchangeService.get(exchangeName);
    if (exchange instanceof StreamingExchange) {
      SafelyDispose.of(disposablesPerExchange.removeAll(exchangeName));
      ((StreamingExchange) exchange).disconnect().blockingAwait();
    }
  }

  private void subscribe(String exchangeName, Set<MarketDataSubscription> subscriptions) {

    Builder<MarketDataSubscription> pollingBuilder = ImmutableSet.builder();

    Exchange exchange = exchangeService.get(exchangeName);

    if (isStreamingExchange(exchange)) {
      Set<MarketDataSubscription> streamingSubscriptions = FluentIterable.from(subscriptions).filter(s -> STREAMING_MARKET_DATA.contains(s.type())).toSet();
      if (!streamingSubscriptions.isEmpty()) {
        openSubscriptions(exchangeName, exchange, streamingSubscriptions);
      }
      pollingBuilder.addAll(FluentIterable.from(subscriptions).filter(s -> !STREAMING_MARKET_DATA.contains(s.type())).toSet());
    } else {
      pollingBuilder.addAll(subscriptions);
    }

    Set<MarketDataSubscription> polls = pollingBuilder.build();
    pollsPerExchange.put(exchangeName, pollingBuilder.build());
    LOGGER.debug("Polls now set to: {}", polls);
  }


  private void openSubscriptions(String exchangeName, Exchange exchange, Set<MarketDataSubscription> streamingSubscriptions) {
    subscriptionsPerExchange.put(exchangeName, streamingSubscriptions);
    subscribeExchange((StreamingExchange)exchange, streamingSubscriptions, exchangeName);

    StreamingMarketDataService streaming = ((StreamingExchange)exchange).getStreamingMarketDataService();
    disposablesPerExchange.putAll(
      exchangeName,
      FluentIterable.from(streamingSubscriptions).transform(sub -> {
        switch (sub.type()) {
          case ORDERBOOK:
            return streaming.getOrderBook(sub.spec().currencyPair())
                .map(t -> OrderBookEvent.create(sub.spec(), t))
                .subscribe(this::onOrderBook, e -> LOGGER.error("Error in order book stream for " + sub, e));
          case TICKER:
            LOGGER.debug("Subscribing to {}", sub.spec());
            return streaming.getTicker(sub.spec().currencyPair())
                .map(t -> TickerEvent.create(sub.spec(), t))
                .subscribe(this::onTicker, e -> LOGGER.error("Error in ticker stream for " + sub, e));
          case TRADES:
            return streaming.getTrades(sub.spec().currencyPair())
                .map(t -> TradeEvent.create(sub.spec(), Trade.create(exchangeName, t)))
                .subscribe(this::onTrade, e -> LOGGER.error("Error in trade stream for " + sub, e));
          default:
            throw new IllegalStateException("Unexpected market data type: " + sub.type());
        }
      })
    );
  }

  private void onTicker(TickerEvent e) {
    logTicker("onTicker", e);
    if (tickerEmitter.get() != null)
      tickerEmitter.get().onNext(e);
  }

  private void onTrade(TradeEvent e) {
    if (tradesEmitter.get() != null)
      tradesEmitter.get().onNext(e);
  }

  private void onOrderBook(OrderBookEvent e) {
    if (orderBookEmitter.get() != null)
      orderBookEmitter.get().onNext(e);
  }

  private void onOpenOrders(OpenOrdersEvent e) {
    if (openOrdersEmitter.get() != null)
      openOrdersEmitter.get().onNext(e);
  }

  private void onTradeHistory(TradeHistoryEvent e) {
    if (tradeHistoryEmitter.get() != null)
      tradeHistoryEmitter.get().onNext(e);
  }

  private void onBalance(BalanceEvent e) {
    if (balanceEmitter.get() != null)
      balanceEmitter.get().onNext(e);
  }

  private void subscribeExchange(StreamingExchange streamingExchange, Collection<MarketDataSubscription> subscriptionsForExchange, String exchangeName) {
    if (subscriptionsForExchange.isEmpty())
      return;
    LOGGER.info("Connecting to exchange: " + exchangeName);
    openConnections(streamingExchange, subscriptionsForExchange);
    LOGGER.info("Connected to exchange: " + exchangeName);
  }

  private void openConnections(StreamingExchange streamingExchange, Collection<MarketDataSubscription> subscriptionsForExchange) {
    ProductSubscriptionBuilder builder = ProductSubscription.create();
    subscriptionsForExchange.stream()
      .forEach(s -> {
        if (s.type().equals(TICKER)) {
          builder.addTicker(s.spec().currencyPair());
        }
        if (s.type().equals(ORDERBOOK)) {
          builder.addOrderbook(s.spec().currencyPair());
        }
        if (s.type().equals(TRADES)) {
          builder.addTrades(s.spec().currencyPair());
        }
      });
    streamingExchange.connect(builder.build()).blockingAwait();
  }

  @Override
  protected void run() {
    Thread.currentThread().setName("Market data subscription manager");
    LOGGER.info("{} started", this);

    final ForkJoinWorkerThreadFactory factory = pool -> {
      final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
      worker.setName(MarketDataSubscriptionManager.class.getSimpleName() + "-" + worker.getPoolIndex());
      return worker;
    };

    ForkJoinPool forkJoinPool = new ForkJoinPool(exchangeService.getExchanges().size() + 1, factory, null, false);
    try {
      forkJoinPool.submit(() ->
        exchangeService.getExchanges()
          .stream()
          .parallel()
          .forEach(this::pollExchange)
      ).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (ExecutionException e1) {
      throw new RuntimeException(e1);
    }
    updateSubscriptions(emptySet());
    LOGGER.info(this + " stopped");
  }

  private void pollExchange(String exchangeName) {
    while (isRunning()) {

      LOGGER.debug("{} start subscription check", this);
      doSubscriptionChanges(exchangeName);

      LOGGER.debug("{} start poll", this);

      Collection<MarketDataSubscription> polls = pollsPerExchange.get(exchangeName);

      Set<String> balanceCurrencies = new HashSet<>();

      for (MarketDataSubscription subscription : polls) {
        if (!isRunning())
          break;
        if (subscription.type().equals(MarketDataType.BALANCE)) {
          balanceCurrencies.add(subscription.spec().base());
          balanceCurrencies.add(subscription.spec().counter());
        } else {
          fetchAndBroadcast(subscription);

          if (!sleep(exchangeName, polls.size()))
            break;
        }
      }

      // We'll be extending this sort of batching to more market data types...
      if (!balanceCurrencies.isEmpty()) {
        fetchBalances(exchangeName, balanceCurrencies)
          .forEach(b -> onBalance(BalanceEvent.create(exchangeName, b.currency(), b)));

        if (!sleep(exchangeName, polls.size()))
          break;
      }

    }
  }

  private boolean sleep(String exchangeName, int pollCount) {
    LOGGER.debug("{} going to sleep", this);
    try {
      Thread.sleep(sleepTime(exchangeName, pollCount));
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private long sleepTime(String exchangeName, int pollCount) {
    try {

      RateLimit[] privateRateLimits = exchangeService.get(exchangeName)
        .getExchangeMetaData()
        .getPrivateRateLimits();

      if (privateRateLimits == null) {
        return defaultSleep(pollCount);
      }

      return Arrays.asList(privateRateLimits)
        .stream()
        .map(RateLimit::getPollDelayMillis)
        .max(Comparator.naturalOrder())
        .orElse(defaultSleep(pollCount));

    } catch (Exception e) {
      LOGGER.warn("Failed to fetch exchange metadata for " + exchangeName, e);
      return defaultSleep(pollCount);
    }
  }

  private long defaultSleep(int pollCount) {
    return (configuration.getLoopSeconds() * 1000) / pollCount;
  }

  private Iterable<Balance> fetchBalances(String exchangeName, Collection<String> currencyCodes) {
    try {
      return FluentIterable.from(exchangeWallet(exchangeName).getBalances().entrySet())
        .transform(Map.Entry::getValue)
        .filter(balance -> currencyCodes.contains(balance.getCurrency().getCurrencyCode()))
        .transform(Balance::create);
    } catch (NotAvailableFromExchangeException e) {
      LOGGER.warn("Balance not available on {}" , exchangeName);
      return Collections.emptyList();
    } catch (Throwable e) {
      LOGGER.error("Error fetching balance on " + exchangeName, e);
      return Collections.emptyList();
    }
  }

  private Wallet exchangeWallet(String exchangeName) throws IOException {
    if (exchangeName.equals("bitfinex")) {
      return accountServiceFactory.getForExchange(exchangeName)
          .getAccountInfo()
          .getWallet("exchange");
    } else {
      return accountServiceFactory.getForExchange(exchangeName)
        .getAccountInfo()
        .getWallet();
    }
  }

  private void fetchAndBroadcast(MarketDataSubscription subscription) {
    try {
      TradeService tradeService;
      TickerSpec spec = subscription.spec();
      MarketDataService marketDataService = exchangeService.get(spec.exchange()).getMarketDataService();
      switch (subscription.type()) {
        case TICKER:
          onTicker(TickerEvent.create(spec, marketDataService.getTicker(spec.currencyPair())));
          break;
        case ORDERBOOK:
          if (spec.exchange().equals("cryptopia")) {
            // TODO submit a PR to xChange for this
            long longValue = Integer.valueOf(ORDERBOOK_DEPTH).longValue();
            onOrderBook(OrderBookEvent.create(spec, marketDataService.getOrderBook(spec.currencyPair(), longValue, longValue)));
          } else {
            onOrderBook(OrderBookEvent.create(spec, marketDataService.getOrderBook(spec.currencyPair(), ORDERBOOK_DEPTH, ORDERBOOK_DEPTH)));
          }
          break;
        case TRADES:
          // TODO need to return only the new ones onTrade(TradeEvent.create(spec, marketDataService.getTrades(spec.currencyPair())));
          throw new UnsupportedOperationException("Trades not supported yet");
        case OPEN_ORDERS:
          tradeService = tradeServiceFactory.getForExchange(subscription.spec().exchange());
          OpenOrdersParams openOrdersParams = openOrdersParams(subscription, tradeService);
          onOpenOrders(OpenOrdersEvent.create(spec, tradeService.getOpenOrders(openOrdersParams)));
          break;
        case USER_TRADE_HISTORY:
          tradeService = tradeServiceFactory.getForExchange(subscription.spec().exchange());
          TradeHistoryParams tradeHistoryParams = tradeHistoryParams(subscription, tradeService);
          ImmutableList<Trade> trades = FluentIterable.from(tradeService.getTradeHistory(tradeHistoryParams).getUserTrades())
            .transform(t -> Trade.create(subscription.spec().exchange(), t))
            .toList();
          onTradeHistory(TradeHistoryEvent.create(spec, trades));
          break;
        default:
          throw new IllegalStateException("Market data type " + subscription.type() + " not supported in this way");
      }
    } catch (NotAvailableFromExchangeException e) {
      LOGGER.warn(subscription.type() + " not available on " + subscription.spec().exchange());
    } catch (Throwable e) {
      LOGGER.error("Error fetching market data: " + subscription, e);
    }
  }


  private TradeHistoryParams tradeHistoryParams(MarketDataSubscription subscription, TradeService tradeService) {
    TradeHistoryParams params;

    // TODO fix with pull request
    if (subscription.spec().exchange().startsWith("gdax")) {
      params = new TradeHistoryParamCurrencyPair() {

        private CurrencyPair pair;

        @Override
        public void setCurrencyPair(CurrencyPair pair) {
          this.pair = pair;
        }

        @Override
        public CurrencyPair getCurrencyPair() {
          return pair;
        }
      };
    } else {
      params = tradeService.createTradeHistoryParams();
    }

    if (params instanceof TradeHistoryParamCurrencyPair) {
      ((TradeHistoryParamCurrencyPair) params).setCurrencyPair(subscription.spec().currencyPair());
    } else {
      throw new UnsupportedOperationException("Don't know how to read user trades on this exchange: " + subscription.spec().exchange());
    }
    if (params instanceof TradeHistoryParamLimit) {
      ((TradeHistoryParamLimit) params).setLimit(MAX_TRADES);
    }
    if (params instanceof TradeHistoryParamPaging) {
      ((TradeHistoryParamPaging) params).setPageLength(MAX_TRADES);
      ((TradeHistoryParamPaging) params).setPageNumber(0);
    }
    return params;
  }

  private OpenOrdersParams openOrdersParams(MarketDataSubscription subscription, TradeService tradeService) {
    OpenOrdersParams params = tradeService.createOpenOrdersParams();
    if (params == null) {
      // Bitfinex
      params = new DefaultOpenOrdersParamCurrencyPair(subscription.spec().currencyPair());
    } else if (params instanceof OpenOrdersParamCurrencyPair) {
      ((OpenOrdersParamCurrencyPair) params).setCurrencyPair(subscription.spec().currencyPair());
    } else {
      throw new UnsupportedOperationException("Don't know how to read open orders on this exchange: " + subscription.spec().exchange());
    }
    return params;
  }

  private boolean isStreamingExchange(Exchange exchange) {
    return exchange instanceof StreamingExchange;
  }

  private void logTicker(String context, TickerEvent e) {
    LOGGER.debug("Ticker [{}] ({}) {}/{} = {}", context, e.spec().exchange(), e.spec().base(), e.spec().counter(), e.ticker().getLast());
  }
}
