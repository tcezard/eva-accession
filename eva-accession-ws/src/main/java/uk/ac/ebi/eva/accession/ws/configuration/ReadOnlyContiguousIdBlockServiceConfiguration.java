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
package uk.ac.ebi.eva.accession.ws.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.ac.ebi.ampt2d.commons.accession.block.initialization.BlockParameters;
import uk.ac.ebi.ampt2d.commons.accession.persistence.jpa.monotonic.service.ContiguousIdBlockService;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only classes for the combination of {@code SpringDataContiguousIdServiceConfiguration} (in accession-commons,
 * enabled via {@code @EnableSpringDataContiguousIdService}) and eva-accession's
 * {@link uk.ac.ebi.eva.accession.core.configuration.ContiguousIdBlocksDataSourceConfiguration}, which together
 * provide the ContiguousIdBlockService bean that {@code SubmittedVariantAccessioningConfiguration} and its
 * siblings depend on.
 *
 * eva-accession-ws only ever reads existing submitted/clustered variants; it never generates new accessions.
 * ContiguousIdBlockService.getBlockParameters(categoryId) (used to compute the ss/rs accessioning thresholds) is a
 * pure in-memory lookup that never touches the database (see ContiguousIdBlockService.java in accession-commons:
 * only reserveNewBlock/save/reserveFirstUncompletedBlockForCategoryIdAndApplicationInstanceId touch the JPA
 * repository, and none of those are on eva-accession-ws's read paths). But the original's JPA-backed wiring still
 * requires an EntityManagerFactory/DataSource to be constructed at startup regardless, which is why this webservice
 * used to require a live "continuous.id.blocks" PostgreSQL connection just to boot.
 *
 * The {@code contiguousBlockInitializations()}/{@code contiguousIdBlockService()} bodies below are copied from
 * {@code SpringDataContiguousIdServiceConfiguration}; the one deliberate difference (marked below) is passing
 * {@code null} instead of a real, JPA-backed {@code ContiguousIdBlockRepository} - safe here because that
 * repository is never touched by the read-only code paths this bean actually serves.
 *
 * {@code @ConditionalOnMissingBean} makes this back off whenever a real, JPA-backed ContiguousIdBlockService is
 * already present in the context (e.g. tests that explicitly import the write-capable core configuration to seed
 * data), so it never shadows a functional bean with this read-only, repository-less one.
 */
@Configuration
@ConditionalOnMissingBean(name = "contiguousIdBlockService")
public class ReadOnlyContiguousIdBlockServiceConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "accessioning.monotonic")
    public HashMap<String, HashMap<String, String>> contiguousBlockInitializations() {
        return new HashMap<>();
    }

    @Bean
    public ContiguousIdBlockService contiguousIdBlockService() {
        Map<String, BlockParameters> blockParameters = contiguousBlockInitializations().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new BlockParameters(entry.getKey(), entry.getValue())));
        // Real ContiguousIdBlockService construction passes a JPA-backed ContiguousIdBlockRepository
        // here instead of null.
        return new ContiguousIdBlockService(null, blockParameters);
    }
}
