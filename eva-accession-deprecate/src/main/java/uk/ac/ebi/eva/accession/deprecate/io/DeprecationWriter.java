/*
 * Copyright 2019 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.ebi.eva.accession.deprecate.io;

import com.mongodb.BulkWriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemWriter;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import uk.ac.ebi.ampt2d.commons.accession.core.models.EventType;
import uk.ac.ebi.ampt2d.commons.accession.persistence.mongodb.document.AccessionedDocument;

import uk.ac.ebi.eva.accession.core.persistence.DbsnpClusteredVariantEntity;
import uk.ac.ebi.eva.accession.core.persistence.DbsnpClusteredVariantInactiveEntity;
import uk.ac.ebi.eva.accession.core.persistence.DbsnpClusteredVariantOperationEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DeprecationWriter implements ItemWriter<DbsnpClusteredVariantEntity> {

    private static final Logger logger = LoggerFactory.getLogger(DeprecationWriter.class);

    private static final String DBSNP_CLUSTERED_VARIANT_DECLUSTERED_COLLECTION_NAME =
            "dbsnpClusteredVariantEntityDeclustered";

    private static final String ID_FIELD = "_id";

    private MongoTemplate mongoTemplate;

    public DeprecationWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void write(List<? extends DbsnpClusteredVariantEntity> deprecableClusteredVariants) throws Exception {
        try {
            insertDeprecateOperation(deprecableClusteredVariants);
            removeDeprecableClusteredVariants(deprecableClusteredVariants);
        } catch (BulkOperationException e) {
            BulkWriteResult bulkWriteResult = e.getResult();
            logger.error("Deprecation writer failed. written operations: {}, removed rs ids: {}",
                        bulkWriteResult.getInsertedCount(), bulkWriteResult.getRemovedCount());
            getAccessions(deprecableClusteredVariants).forEach(a -> logger.error("rs id: " + a));
            throw e;
        }
    }

    private List<String> getAccessions(List<? extends DbsnpClusteredVariantEntity> deprecableClusteredVariants) {
        return deprecableClusteredVariants.stream()
                                          .map(AccessionedDocument::getAccession)
                                          .map(Objects::toString)
                                          .collect(Collectors.toList());
    }

    private void removeDeprecableClusteredVariants(
            List<? extends DbsnpClusteredVariantEntity> deprecableClusteredVatiants) {
        BulkOperations bulkOperations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
                                                              DbsnpClusteredVariantEntity.class,
                                                              DBSNP_CLUSTERED_VARIANT_DECLUSTERED_COLLECTION_NAME);
        Query query = new Query();
        query.addCriteria(Criteria.where(ID_FIELD).in(getIds(deprecableClusteredVatiants)));
        bulkOperations.remove(query);
        bulkOperations.execute();
    }

    private List<String> getIds(List<? extends DbsnpClusteredVariantEntity> deprecableClusteredVariants) {
        return deprecableClusteredVariants.stream().map(AccessionedDocument::getId).collect(Collectors.toList());
    }

    private void insertDeprecateOperation(List<? extends DbsnpClusteredVariantEntity> deprecableClusteredVatiants) {
        BulkOperations bulkOperations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
                                                              DbsnpClusteredVariantOperationEntity.class);
        bulkOperations.insert(createOperations(deprecableClusteredVatiants));
        bulkOperations.execute();
    }

    private List<DbsnpClusteredVariantOperationEntity> createOperations(
            List<? extends DbsnpClusteredVariantEntity> deprecableClusteredVatiants) {
        List<DbsnpClusteredVariantOperationEntity> operations = new ArrayList<>();
        for (DbsnpClusteredVariantEntity deprecableClusteredVatiant : deprecableClusteredVatiants) {
            DbsnpClusteredVariantInactiveEntity inactiveEntity = new DbsnpClusteredVariantInactiveEntity(
                    deprecableClusteredVatiant);
            DbsnpClusteredVariantOperationEntity operation = new DbsnpClusteredVariantOperationEntity();
            operation.fill(EventType.DEPRECATED, deprecableClusteredVatiant.getAccession(), null,
                           "Clustered variant completely declustered", Collections.singletonList(inactiveEntity));
            operations.add(operation);
        }
        return operations;
    }
}