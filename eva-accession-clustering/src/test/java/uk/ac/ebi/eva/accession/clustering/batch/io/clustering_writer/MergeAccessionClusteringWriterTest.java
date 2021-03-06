/*
 * Copyright 2020 EMBL - European Bioinformatics Institute
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
package uk.ac.ebi.eva.accession.clustering.batch.io.clustering_writer;

import com.lordofthejars.nosqlunit.mongodb.MongoDbConfigurationBuilder;
import com.lordofthejars.nosqlunit.mongodb.MongoDbRule;
import org.assertj.core.util.Sets;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import uk.ac.ebi.ampt2d.commons.accession.core.exceptions.AccessionCouldNotBeGeneratedException;
import uk.ac.ebi.ampt2d.commons.accession.core.models.EventType;
import uk.ac.ebi.ampt2d.commons.accession.hashing.SHA1HashingFunction;
import uk.ac.ebi.ampt2d.commons.accession.persistence.jpa.monotonic.repositories.ContiguousIdBlockRepository;
import uk.ac.ebi.ampt2d.commons.accession.persistence.mongodb.document.EventDocument;

import uk.ac.ebi.eva.accession.clustering.batch.io.ClusteringWriter;
import uk.ac.ebi.eva.accession.clustering.parameters.InputParameters;
import uk.ac.ebi.eva.accession.clustering.test.configuration.BatchTestConfiguration;
import uk.ac.ebi.eva.accession.clustering.test.rule.FixSpringMongoDbRule;
import uk.ac.ebi.eva.accession.core.configuration.nonhuman.ClusteredVariantAccessioningConfiguration;
import uk.ac.ebi.eva.accession.core.model.ClusteredVariant;
import uk.ac.ebi.eva.accession.core.model.IClusteredVariant;
import uk.ac.ebi.eva.accession.core.model.ISubmittedVariant;
import uk.ac.ebi.eva.accession.core.model.SubmittedVariant;
import uk.ac.ebi.eva.accession.core.model.dbsnp.DbsnpClusteredVariantEntity;
import uk.ac.ebi.eva.accession.core.model.dbsnp.DbsnpClusteredVariantOperationEntity;
import uk.ac.ebi.eva.accession.core.model.dbsnp.DbsnpSubmittedVariantEntity;
import uk.ac.ebi.eva.accession.core.model.dbsnp.DbsnpSubmittedVariantOperationEntity;
import uk.ac.ebi.eva.accession.core.model.eva.ClusteredVariantEntity;
import uk.ac.ebi.eva.accession.core.model.eva.ClusteredVariantInactiveEntity;
import uk.ac.ebi.eva.accession.core.model.eva.ClusteredVariantOperationEntity;
import uk.ac.ebi.eva.accession.core.model.eva.SubmittedVariantEntity;
import uk.ac.ebi.eva.accession.core.model.eva.SubmittedVariantInactiveEntity;
import uk.ac.ebi.eva.accession.core.model.eva.SubmittedVariantOperationEntity;
import uk.ac.ebi.eva.accession.core.service.nonhuman.ClusteredVariantAccessioningService;
import uk.ac.ebi.eva.accession.core.summary.ClusteredVariantSummaryFunction;
import uk.ac.ebi.eva.accession.core.summary.SubmittedVariantSummaryFunction;
import uk.ac.ebi.eva.commons.core.models.VariantType;

import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static uk.ac.ebi.eva.accession.clustering.test.VariantAssertions.assertAccessionEqual;
import static uk.ac.ebi.eva.accession.clustering.test.VariantAssertions.assertAssemblyAccessionEqual;
import static uk.ac.ebi.eva.accession.clustering.test.VariantAssertions.assertClusteredVariantAccessionEqual;
import static uk.ac.ebi.eva.accession.clustering.test.VariantAssertions.assertReferenceSequenceAccessionEqual;

/**
 * This class handles some scenarios of ClusteringWriter where redundant RSs are discovered and merged.
 *
 * That includes all the scenarios that involve writing to the Operation collections, e.g.
 * clusteredVariantOperationEntity to register an RS merge.
 * Other test classes in this folder take care of other scenarios.
 */
@RunWith(SpringRunner.class)
@EnableAutoConfiguration
@ContextConfiguration(classes = {ClusteredVariantAccessioningConfiguration.class, BatchTestConfiguration.class})
@TestPropertySource("classpath:clustering-pipeline-test.properties")
public class MergeAccessionClusteringWriterTest {

    private static final String TEST_DB = "test-db";

    private static final String CLUSTERED_VARIANT_COLLECTION = "clusteredVariantEntity";

    private static final String SUBMITTED_VARIANT_COLLECTION = "submittedVariantEntity";

    public static final String DBSNP_CLUSTERED_VARIANT_COLLECTION = "dbsnpClusteredVariantEntity";

    public static final String DBSNP_SUBMITTED_VARIANT_COLLECTION = "dbsnpSubmittedVariantEntity";

    public static final long EVA_CLUSTERED_VARIANT_RANGE_START = 3000000000L;

    public static final long EVA_SUBMITTED_VARIANT_RANGE_START = 5000000000L;

    @Autowired
    private InputParameters inputParameters;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ClusteredVariantAccessioningService clusteredVariantAccessioningService;

    @Autowired
    private ContiguousIdBlockRepository contiguousIdBlockRepository;

    //Required by nosql-unit
    @Autowired
    private ApplicationContext applicationContext;

    private ClusteringWriter clusteringWriter;

    private Function<ISubmittedVariant, String> hashingFunction;

    private Function<IClusteredVariant, String> clusteredHashingFunction;

    @Rule
    public MongoDbRule mongoDbRule = new FixSpringMongoDbRule(
            MongoDbConfigurationBuilder.mongoDb().databaseName(TEST_DB).build());

    @Before
    public void setUp() {
        mongoTemplate.getDb().drop();
        clusteringWriter = new ClusteringWriter(mongoTemplate, clusteredVariantAccessioningService,
                                                EVA_SUBMITTED_VARIANT_RANGE_START, EVA_CLUSTERED_VARIANT_RANGE_START);
        hashingFunction = new SubmittedVariantSummaryFunction().andThen(new SHA1HashingFunction());
        clusteredHashingFunction = new ClusteredVariantSummaryFunction().andThen(new SHA1HashingFunction());
    }

    @After
    public void tearDown() {
        mongoTemplate.getDb().drop();
    }

    @Test
    @DirtiesContext
    public void merge_eva_clustered_accession() throws Exception {
        Long rs1 = 3000000000L;
        Long rs2 = 3100000000L;
        long ssToRemap = 5000000000L;
        long ss2 = 5100000000L;
        mergeClusteredAccession(rs1, rs2, ssToRemap, ss2, 0, 2, 0, 1, 0, 2, 0, 1);
        assertMergedInto(rs1, rs2, ss2);
    }

    @Test
    @DirtiesContext
    public void merge_eva_clustered_accession_reversed() throws Exception {
        Long rs1 = 3100000000L;
        Long rs2 = 3000000000L;
        Long ssToRemap = 5000000000L;
        Long ss2 = 5100000000L;
        mergeClusteredAccession(rs1, rs2, ssToRemap, ss2, 0, 2, 0, 1, 0, 2, 0, 1);
        assertMergedInto(rs2, rs1, ssToRemap);
    }

    @Test
    @DirtiesContext
    public void merge_dbsnp_clustered_accession() throws Exception {
        Long rs1 = 30L;
        Long rs2 = 31L;
        Long ssToRemap = 50L;
        Long ss2 = 51L;
        mergeClusteredAccession(rs1, rs2, ssToRemap, ss2, 2, 0, 1, 0, 2, 0, 1, 0);
        assertMergedInto(rs1, rs2, ss2);
    }

    @Test
    @DirtiesContext
    public void merge_eva_into_dbsnp_clustered_accession() throws Exception {
        Long rs1 = 3000000000L;
        Long rs2 = 31L;
        Long ssToRemap = 5000000000L;
        Long ss2 = 51L;
        mergeClusteredAccession(rs1, rs2, ssToRemap, ss2, 1, 1, 0, 1, 1, 1, 0, 1);
        assertMergedInto(rs2, rs1, ssToRemap);
    }

    @Test
    @DirtiesContext
    public void do_not_merge_dbsnp_into_eva_clustered_accession() throws Exception {
        Long rs1 = 30L;
        Long rs2 = 3100000000L;
        Long ssToRemap = 50L;
        Long ss2 = 5100000000L;
        mergeClusteredAccession(rs1, rs2, ssToRemap, ss2, 1, 1, 0, 1, 1, 1, 0, 1);
        assertMergedInto(rs1, rs2, ss2);
    }

    public void mergeClusteredAccession(Long rs1, Long rs2, Long ssToRemap, Long ss2,
                                        int expectedDbsnpCve, int expectedCve,
                                        int expectedDbsnpCvOperations, int expectedCvOperations,
                                        int expectedDbsnpSve, int expectedSve,
                                        int expectedDbsnpSvOperations, int expectedSvOperations)
            throws Exception {
        // given
        String asm1 = "asm1";
        String asm2 = "asm2";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        mongoTemplate.insert(createClusteredVariantEntity(asm1, rs1), getClusteredTable(rs1));

        // rs2 will be merged into rs1, but the document will be kept to describe how rs1 maps into this second assembly
        mongoTemplate.insert(createClusteredVariantEntity(asm2, rs2), getClusteredTable(rs2));

        // ssToRemap in the old assembly, will be remapped to asm2 (see sve1Remapped below)
        mongoTemplate.insert(createSubmittedVariantEntity(asm1, rs1, ssToRemap), getSubmittedTable(ssToRemap));

        // ss2 in the new assembly, will change its RS to rs1 when we realise rs1 and rs2 should be merged
        // because they both map to the same position in asm2
        mongoTemplate.insert(createSubmittedVariantEntity(asm2, rs2, ss2), getSubmittedTable(ss2));

        assertDatabaseCounts(expectedDbsnpCve, expectedCve, 0, 0,
                             expectedDbsnpSve, expectedSve, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(expectedDbsnpCve, expectedCve, expectedDbsnpCvOperations, expectedCvOperations,
                             expectedDbsnpSve, expectedSve, expectedDbsnpSvOperations, expectedSvOperations);


        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2));
    }

    private ClusteredVariantEntity createClusteredVariantEntity(String assembly, Long rs) {
        ClusteredVariant cv = new ClusteredVariant(assembly, 1000, "1", 100L, VariantType.SNV, false, null);
        String cvHash = clusteredHashingFunction.apply(cv);
        return new ClusteredVariantEntity(rs, cvHash, cv, 1);
    }

    private SubmittedVariantEntity createSubmittedVariantEntity(String assembly, Long rs, Long ss) {
        SubmittedVariant submittedClustered = new SubmittedVariant(assembly, 1000, "project", "1", 100L, "T", "A", rs);
        String hash1 = hashingFunction.apply(submittedClustered);
        return new SubmittedVariantEntity(ss, hash1, submittedClustered, 1);
    }

    private String getClusteredTable(Long rs1) {
        return isEvaClusteredVariant(rs1) ? CLUSTERED_VARIANT_COLLECTION : DBSNP_CLUSTERED_VARIANT_COLLECTION;
    }

    private boolean isEvaClusteredVariant(Long rs1) {
        return rs1 >= EVA_CLUSTERED_VARIANT_RANGE_START;
    }

    private String getSubmittedTable(Long ss1) {
        return isEvaSubmittedVariant(ss1) ? SUBMITTED_VARIANT_COLLECTION : DBSNP_SUBMITTED_VARIANT_COLLECTION;
    }

    private boolean isEvaSubmittedVariant(Long ss1) {
        return ss1 >= EVA_SUBMITTED_VARIANT_RANGE_START;
    }

    private void assertDatabaseCounts(int expectedDbsnpCve, int expectedCve,
                                      int expectedDbsnpCvOperations, int expectedCvOperations,
                                      int expectedDbsnpSve, int expectedSve,
                                      int expectedDbsnpSvOperations, int expectedSvOperations) {
        assertEquals(expectedDbsnpCve, mongoTemplate.count(new Query(), DbsnpClusteredVariantEntity.class));
        assertEquals(expectedCve, mongoTemplate.count(new Query(), ClusteredVariantEntity.class));
        assertEquals(expectedDbsnpCvOperations, mongoTemplate.count(new Query(),
                                                                    DbsnpClusteredVariantOperationEntity.class));
        assertEquals(expectedCvOperations, mongoTemplate.count(new Query(), ClusteredVariantOperationEntity.class));
        assertEquals(expectedDbsnpSve, mongoTemplate.count(new Query(), DbsnpSubmittedVariantEntity.class));
        assertEquals(expectedSve, mongoTemplate.count(new Query(), SubmittedVariantEntity.class));
        assertEquals(expectedDbsnpSvOperations, mongoTemplate.count(new Query(),
                                                                    DbsnpSubmittedVariantOperationEntity.class));
        assertEquals(expectedSvOperations, mongoTemplate.count(new Query(), SubmittedVariantOperationEntity.class));
    }

    private void assertMergedInto(Long mergedInto, Long originalAccession, Long... updatedSubmittedVariant) {
        List<SubmittedVariantEntity> submittedVariants = mongoTemplate.findAll(SubmittedVariantEntity.class);
        submittedVariants.addAll(mongoTemplate.findAll(DbsnpSubmittedVariantEntity.class));
        assertClusteredVariantAccessionEqual(Sets.newTreeSet(mergedInto), submittedVariants);

        List<ClusteredVariantEntity> clusteredVariants = mongoTemplate.findAll(ClusteredVariantEntity.class);
        clusteredVariants.addAll(mongoTemplate.findAll(DbsnpClusteredVariantEntity.class));
        assertAccessionEqual(Sets.newTreeSet(mergedInto), clusteredVariants);

        EventDocument<IClusteredVariant, Long, ? extends ClusteredVariantInactiveEntity> clusteredOp =
                mongoTemplate.findOne(new Query(), ClusteredVariantOperationEntity.class);
        if (clusteredOp == null) {
            clusteredOp = mongoTemplate.findOne(new Query(), DbsnpClusteredVariantOperationEntity.class);
        }
        assertEquals(EventType.MERGED, clusteredOp.getEventType());
        assertEquals(originalAccession, clusteredOp.getAccession());
        assertEquals(mergedInto, clusteredOp.getMergedInto());


        List<? extends EventDocument<ISubmittedVariant, Long, ? extends SubmittedVariantInactiveEntity>> submittedOps =
                mongoTemplate.findAll(SubmittedVariantOperationEntity.class);
        if (submittedOps.isEmpty()) {
            submittedOps = mongoTemplate.findAll(DbsnpSubmittedVariantOperationEntity.class);
        }
        assertTrue(submittedOps.stream().map(EventDocument::getEventType).allMatch(EventType.UPDATED::equals));
        assertEquals(Sets.newTreeSet(updatedSubmittedVariant), submittedOps.stream()
                                                                           .map(EventDocument::getAccession)
                                                                           .collect(Collectors.toSet()));
    }

    @Test
    public void do_not_merge_into_remapped_multimap_variants() throws Exception {
        // given
        Long rs1 = 3000000000L;
        Long rs2 = 3100000000L;
        Long ssToRemap = 5000000000L;
        Long ss2 = 5100000000L;
        Long ss3 = 5200000000L;
        String asm1 = "asm1";
        String asm2 = "asm2";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        mongoTemplate.insert(createClusteredVariantEntity(asm1, 100L, rs1), getClusteredTable(rs1));
        mongoTemplate.insert(createClusteredVariantEntity(asm1, 200L, rs1), getClusteredTable(rs1));

        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 100L, rs1, ssToRemap), getSubmittedTable(ssToRemap));
        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 200L, rs1, ss3), getSubmittedTable(ssToRemap));


        // NOTE: rs2 won't be merged into rs1 because rs1 is multimap
        mongoTemplate.insert(createClusteredVariantEntity(asm2, 100L, rs2), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 100L, rs2, ss2), getSubmittedTable(ss2));

        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, 100L, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2));
    }

    @Test
    public void do_not_merge_remapped_multimap_variants() throws Exception {
        // given
        Long rs1 = 3100000000L;
        Long rs2 = 3000000000L;
        Long ssToRemap = 5500000000L;
        Long ss2 = 5100000000L;
        Long ss3 = 5200000000L;
        String asm1 = "asm1";
        String asm2 = "asm2";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        // NOTE: rs1 won't be merged into rs2 because rs1 is multimap
        mongoTemplate.insert(createClusteredVariantEntity(asm1, 100L, rs1), getClusteredTable(rs1));
        mongoTemplate.insert(createClusteredVariantEntity(asm1, 200L, rs1), getClusteredTable(rs1));

        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 100L, rs1, ssToRemap), getSubmittedTable(ssToRemap));
        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 200L, rs1, ss3), getSubmittedTable(ssToRemap));


        mongoTemplate.insert(createClusteredVariantEntity(asm2, 100L, rs2), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 100L, rs2, ss2), getSubmittedTable(ss2));

        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, 100L, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2));
    }

    @Test
    public void do_not_merge_multimap_variants_into_remapped_variants() throws Exception {
        // given
        Long rs1 = 3000000000L;
        Long rs2 = 3100000000L;
        Long ssToRemap = 5000000000L;
        Long ss2 = 5100000000L;
        Long ss3 = 5200000000L;
        String asm1 = "asm1";
        String asm2 = "asm2";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        mongoTemplate.insert(createClusteredVariantEntity(asm1, 100L, rs1), getClusteredTable(rs1));

        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 100L, rs1, ssToRemap), getSubmittedTable(ssToRemap));


        // NOTE: rs2 won't be merged into rs1 because rs2 is multimap
        mongoTemplate.insert(createClusteredVariantEntity(asm2, 200L, rs2), getClusteredTable(rs2));
        mongoTemplate.insert(createClusteredVariantEntity(asm2, 300L, rs2), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 200L, rs2, ss2), getSubmittedTable(ss2));
        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 300L, rs2, ss3), getSubmittedTable(ss2));

        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, 200L, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2));
    }

    @Test
    public void do_not_merge_remapped_variant_into_multimap_variants() throws Exception {
        // given
        Long rs1 = 3100000000L;
        Long rs2 = 3000000000L;
        Long ssToRemap = 5500000000L;
        Long ss2 = 5100000000L;
        Long ss3 = 5200000000L;
        String asm1 = "asm1";
        String asm2 = "asm2";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        // NOTE rs1 won't be merged into rs2 because rs2 is multimap
        mongoTemplate.insert(createClusteredVariantEntity(asm1, 100L, rs1), getClusteredTable(rs1));

        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 100L, rs1, ssToRemap), getSubmittedTable(ssToRemap));


        mongoTemplate.insert(createClusteredVariantEntity(asm2, 200L, rs2), getClusteredTable(rs2));
        mongoTemplate.insert(createClusteredVariantEntity(asm2, 300L, rs2), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 200L, rs2, ss2), getSubmittedTable(ss2));
        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 300L, rs2, ss3), getSubmittedTable(ss2));

        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, 200L, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2));
    }

    @Test
    @DirtiesContext
    public void merge_into_remapped_multimap_variants_if_single_mapping_per_assembly()
            throws AccessionCouldNotBeGeneratedException {
        // given
        Long rs1 = 3000000000L;
        Long rs2 = 3100000000L;
        Long ssToRemap = 5000000000L;
        Long ss2 = 5100000000L;
        Long ss3 = 5200000000L;
        String asm1 = "asm1";
        String asm2 = "asm2";
        String asm3 = "asm3";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        // NOTE: merging into rs1 here is allowed because rs1 maps once in asm1 and once in asm3
        mongoTemplate.insert(createClusteredVariantEntity(asm1, 100L, rs1), getClusteredTable(rs1));
        mongoTemplate.insert(createClusteredVariantEntity(asm3, 300L, rs1), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 100L, rs1, ssToRemap), getSubmittedTable(ssToRemap));
        mongoTemplate.insert(createSubmittedVariantEntity(asm3, 300L, rs1, ss3), getSubmittedTable(ss3));


        mongoTemplate.insert(createClusteredVariantEntity(asm2, 200L, rs2), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 200L, rs2, ss2), getSubmittedTable(ss2));

        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, 200L, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(0, 3, 0, 1,
                             0, 3, 0, 1);

        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2, asm3));
        assertMergedInto(rs1, rs2, ss2);
    }

    @Test
    @DirtiesContext
    public void merge_multimap_variants_into_remapped_variants_if_single_mapping_per_assembly()
            throws AccessionCouldNotBeGeneratedException {
        // given
        Long rs1 = 3000000000L;
        Long rs2 = 3100000000L;
        Long ssToRemap = 5000000000L;
        Long ss2 = 5100000000L;
        Long ss3 = 5200000000L;
        String asm1 = "asm1";
        String asm2 = "asm2";
        String asm3 = "asm3";
        assertDatabaseCounts(0, 0, 0, 0, 0, 0, 0, 0);

        mongoTemplate.insert(createClusteredVariantEntity(asm1, 100L, rs1), getClusteredTable(rs1));

        mongoTemplate.insert(createSubmittedVariantEntity(asm1, 100L, rs1, ssToRemap), getSubmittedTable(ssToRemap));


        // NOTE: merging into rs1 here is allowed because rs2 maps once in asm2 and once in asm3
        mongoTemplate.insert(createClusteredVariantEntity(asm2, 200L, rs2), getClusteredTable(rs2));
        mongoTemplate.insert(createClusteredVariantEntity(asm3, 300L, rs2), getClusteredTable(rs2));

        mongoTemplate.insert(createSubmittedVariantEntity(asm2, 200L, rs2, ss2), getSubmittedTable(ss2));
        mongoTemplate.insert(createSubmittedVariantEntity(asm3, 300L, rs2, ss3), getSubmittedTable(ss3));

        assertDatabaseCounts(0, 3, 0, 0,
                             0, 3, 0, 0);

        // when
        SubmittedVariantEntity sve1Remapped = createSubmittedVariantEntity(asm2, 200L, rs1, ssToRemap);
        clusteringWriter.write(Collections.singletonList(sve1Remapped));

        // then
        assertDatabaseCounts(0, 3, 0, 2,
                             0, 3, 0, 2);

        assertAssembliesPresent(Sets.newTreeSet(asm1, asm2, asm3));
        assertMergedInto(rs1, rs2, ss2, ss3);
    }

    private void assertAssembliesPresent(TreeSet<String> expectedAssemblies) {
        List<SubmittedVariantEntity> submittedVariants = mongoTemplate.findAll(SubmittedVariantEntity.class);
        submittedVariants.addAll(mongoTemplate.findAll(DbsnpSubmittedVariantEntity.class));
        assertReferenceSequenceAccessionEqual(expectedAssemblies, submittedVariants);

        List<ClusteredVariantEntity> clusteredVariants = mongoTemplate.findAll(ClusteredVariantEntity.class);
        clusteredVariants.addAll(mongoTemplate.findAll(DbsnpClusteredVariantEntity.class));
        assertAssemblyAccessionEqual(expectedAssemblies, clusteredVariants);
    }

    private ClusteredVariantEntity createClusteredVariantEntity(String assembly, Long start, Long rs) {
        ClusteredVariant cv = new ClusteredVariant(assembly, 1000, "1", start, VariantType.SNV, false, null);
        String cvHash = clusteredHashingFunction.apply(cv);
        return new ClusteredVariantEntity(rs, cvHash, cv, 1);
    }

    private SubmittedVariantEntity createSubmittedVariantEntity(String assembly, Long start, Long rs, Long ss) {
        SubmittedVariant submittedClustered = new SubmittedVariant(assembly, 1000, "project", "1", start, "T", "A", rs);
        String hash1 = hashingFunction.apply(submittedClustered);
        return new SubmittedVariantEntity(ss, hash1, submittedClustered, 1);
    }
}
