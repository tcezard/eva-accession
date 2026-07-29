/*
 *
 * Copyright 2026 EMBL - European Bioinformatics Institute
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
 *
 */
package uk.ac.ebi.eva.accession.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import uk.ac.ebi.ampt2d.commons.accession.rest.dto.AccessionResponseDTO;
import uk.ac.ebi.eva.accession.core.contigalias.ContigAliasService;
import uk.ac.ebi.eva.accession.core.model.ISubmittedVariant;
import uk.ac.ebi.eva.accession.core.model.SubmittedVariant;
import uk.ac.ebi.eva.accession.core.test.configuration.nonhuman.MongoTestConfiguration;
import uk.ac.ebi.eva.accession.core.utils.MongoTestContainerHelper;
import uk.ac.ebi.eva.accession.core.utils.MongoTestDataLoader;
import uk.ac.ebi.eva.accession.ws.test.NoContigTranslationArgumentMatcher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

/**
 * Unlike the other REST controller tests in this package, this test does NOT import
 * {@code SubmittedVariantAccessioningConfiguration} or {@code ContiguousIdBlocksDataSourceConfiguration} (the
 * real, JPA/PostgreSQL-backed core configuration). It only relies on the application's own default wiring
 * (EvaAccessionApplication -&gt; ApplicationConfiguration -&gt; the ReadOnly* configurations), the same wiring used
 * in production. Every other test in this package imports the real configuration to get a write-capable
 * accessioning service for seeding data, which means their Spring context always has a bean named
 * "submittedVariantAccessioningService" already present - so ReadOnlySubmittedVariantAccessioningConfiguration's
 * {@code @ConditionalOnMissingBean} always backs off in those tests, and its actual bean-construction code path
 * (including ReadOnlyContiguousIdBlockServiceConfiguration's embedded-HSQLDB EntityManagerFactory) never runs.
 * This test is the one that actually exercises it - test data is seeded directly into MongoDB instead of through
 * a write-capable service, and {@code accession-ws-readonly-test.properties} deliberately has no
 * continuous.id.blocks.datasource.* properties at all.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MongoTestConfiguration.class)
@TestPropertySource("classpath:accession-ws-readonly-test.properties")
public class ReadOnlyConfigurationRestControllerTest extends MongoTestContainerHelper {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ResourceLoader resourceLoader;

    @MockBean
    private ContigAliasService contigAliasService;

    private static class SubmittedVariantType extends ParameterizedTypeReference<
            List<AccessionResponseDTO<SubmittedVariant, ISubmittedVariant, String, Long>>> {
    }

    @Test
    public void getSubmittedVariantThroughReadOnlyConfiguration() {
        when(contigAliasService.translateContigToInsdc(anyString(), anyString(),
                argThat(new NoContigTranslationArgumentMatcher()))).thenCallRealMethod();
        when(contigAliasService.getSubmittedVariantsWithTranslatedContig(any(), any())).thenCallRealMethod();

        new MongoTestDataLoader(mongoTemplate, resourceLoader).loadAll("/test-data/readOnlySubmittedVariant.json");

        ResponseEntity<List<AccessionResponseDTO<SubmittedVariant, ISubmittedVariant, String, Long>>> response =
                testRestTemplate.exchange("/v1/submitted-variants/42", HttpMethod.GET, null,
                        new SubmittedVariantType());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<AccessionResponseDTO<SubmittedVariant, ISubmittedVariant, String, Long>> body = response.getBody();
        assertEquals(1, body.size());
        SubmittedVariant variant = body.get(0).getData();
        assertEquals(42L, body.get(0).getAccession());
        assertEquals("ASM1", variant.getReferenceSequenceAccession());
        assertEquals("chr1", variant.getContig());
        assertEquals(100, variant.getStart());
        assertEquals("C", variant.getReferenceAllele());
        assertEquals("T", variant.getAlternateAllele());
    }
}
