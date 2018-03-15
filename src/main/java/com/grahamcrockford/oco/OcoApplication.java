package com.grahamcrockford.oco;

import java.util.Set;

import javax.inject.Inject;
import javax.ws.rs.client.Client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;

import io.dropwizard.Application;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class OcoApplication extends Application<OcoConfiguration> {

  private static final Logger LOGGER = LoggerFactory.getLogger(OcoApplication.class);

  public static void main(final String[] args) throws Exception {
    new OcoApplication().run(args);
  }

  @Inject private Set<Service> services;
  @Inject private Set<EnvironmentInitialiser> environmentInitialisers;
  @Inject private Set<WebResource> webResources;
  @Inject private Set<Managed> managedTasks;


  @Override
  public String getName() {
    return "Background Trade Control";
  }

  @Override
  public void initialize(final Bootstrap<OcoConfiguration> bootstrap) {
    bootstrap.setConfigurationSourceProvider(
      new SubstitutingSourceProvider(
        bootstrap.getConfigurationSourceProvider(),
        new EnvironmentVariableSubstitutor()
      )
    );
  }

  @Override
  public void run(final OcoConfiguration configuration, final Environment environment) {

    // Jersey client
    final Client jerseyClient = new JerseyClientBuilder(environment).using(configuration.getJerseyClientConfiguration()).build(getName());

    // Injector
    final Injector injector = Guice.createInjector(new OcoModule(configuration, environment.getObjectMapper(), jerseyClient));
    injector.injectMembers(this);

    environment.servlets().addFilter("GuiceFilter", GuiceFilter.class)
      .addMappingForUrlPatterns(java.util.EnumSet.allOf(javax.servlet.DispatcherType.class), true, "/*");

    // Any environment initialisation
    environmentInitialisers.stream()
      .peek(t -> LOGGER.info("Initialising environment for {}", t))
      .forEach(t -> t.init(environment));

    // Any managed tasks
    managedTasks.stream()
      .peek(t -> LOGGER.info("Starting managed task {}", t))
      .forEach(environment.lifecycle()::manage);

    // And any bound services
    services.stream()
      .peek(t -> LOGGER.info("Starting managed task {}", t))
      .map(ManagedServiceTask::new)
      .forEach(environment.lifecycle()::manage);

    // And any REST resources
    webResources.stream()
      .peek(t -> LOGGER.info("Registering resource {}", t))
      .forEach(environment.jersey()::register);
  }
}