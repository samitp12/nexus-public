/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.search;

import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.entity.EntityHelper;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.repository.FacetSupport.State.STARTED;
import static org.sonatype.nexus.repository.search.DefaultComponentMetadataProducer.REPOSITORY_NAME;

/**
 * Default {@link SearchFacet} implementation.
 *
 * Depends on presence of a {@link StorageFacet} attached to {@link Repository}.
 *
 * @since 3.0
 */
@Named
public class SearchFacetImpl
    extends FacetSupport
    implements SearchFacet
{
  private final SearchService searchService;

  private final Map<String, ComponentMetadataProducer> componentMetadataProducers;

  private Map<String, Object> repositoryMetadata;

  @Inject
  public SearchFacetImpl(final SearchService searchService,
                         final Map<String, ComponentMetadataProducer> componentMetadataProducers)
  {
    this.searchService = checkNotNull(searchService);
    this.componentMetadataProducers = checkNotNull(componentMetadataProducers);
  }

  @Override
  protected void doInit(Configuration configuration) throws Exception {
    repositoryMetadata = ImmutableMap.of(REPOSITORY_NAME, getRepository().getName());
    super.doInit(configuration);
  }

  @Override
  @Guarded(by = STARTED)
  public void rebuildIndex() {
    log.info("Rebuilding index of repository {}", getRepository().getName());
    searchService.rebuildIndex(getRepository());
    UnitOfWork.begin(facet(StorageFacet.class).txSupplier());
    try {
      rebuildComponentIndex();
    }
    finally {
      UnitOfWork.end();
    }
  }

  @Transactional
  protected void rebuildComponentIndex() {
    final StorageTx tx = UnitOfWork.currentTx();
    bulkPut(tx, tx.browseComponents(tx.findBucket(getRepository())));
  }

  @Override
  @Guarded(by = STARTED)
  @Transactional
  public void put(final EntityId componentId) {
    checkNotNull(componentId);
    final StorageTx tx = UnitOfWork.currentTx();
    Component component = tx.findComponentInBucket(componentId, tx.findBucket(getRepository()));
    if (component != null) {
      put(tx, component);
    }
  }

  @Override
  @Guarded(by = STARTED)
  @Transactional
  public void bulkPut(final Iterable<EntityId> componentIds) {
    checkNotNull(componentIds);
    final StorageTx tx = UnitOfWork.currentTx();
    final Bucket bucket = tx.findBucket(getRepository());
    bulkPut(tx,
        Iterables.filter(
            Iterables.transform(componentIds, componentId -> tx.findComponentInBucket(componentId, bucket)),
            Objects::nonNull));
  }

  @Override
  @Guarded(by = STARTED)
  public void delete(final EntityId componentId) {
    checkNotNull(componentId);
    searchService.delete(getRepository(), componentId.getValue());
  }

  @Override
  protected void doStart() throws Exception {
    searchService.createIndex(getRepository());
  }

  @Override
  protected void doDelete() {
    searchService.deleteIndex(getRepository());
  }

  /**
   * Extracts metadata from given {@link Component} and its assets, and PUTs it into the repository's index.
   */
  private void put(final StorageTx tx, final Component component) {
    searchService.put(
        getRepository(),
        documentId(component),
        json(component, tx.browseAssets(component)));
  }

  /**
   * Extracts metadata from given {@link Component}s and their assets, and bulk-PUTs them into the repository's index.
   */
  private void bulkPut(final StorageTx tx, final Iterable<Component> components) {
    searchService.bulkPut(
        getRepository(),
        components,
        this::documentId,
        component -> json(component, tx.browseAssets(component)));
  }

  /**
   * Looks for a {@link ComponentMetadataProducer} specific to the component {@link Format}.
   * If one is not available will use a default one ({@link DefaultComponentMetadataProducer}).
   */
  private ComponentMetadataProducer producer(final Component component) {
    checkNotNull(component);
    String format = component.format();
    ComponentMetadataProducer producer = componentMetadataProducers.get(format);
    if (producer == null) {
      producer = componentMetadataProducers.get("default");
    }
    checkState(producer != null, "Could not find a component metadata producer for format: %s", format);
    return producer;
  }

  /**
   * Returns the id of the document representing the given component in the repository's index.
   */
  private String documentId(final Component component) {
    return EntityHelper.id(component).getValue();
  }

  /**
   * Returns the JSON document representing the given component in the repository's index.
   */
  private String json(final Component component, final Iterable<Asset> assets) {
    return producer(component).getMetadata(component, assets, repositoryMetadata);
  }
}
