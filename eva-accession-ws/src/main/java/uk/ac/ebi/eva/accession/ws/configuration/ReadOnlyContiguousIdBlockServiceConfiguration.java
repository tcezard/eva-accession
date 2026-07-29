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
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import uk.ac.ebi.ampt2d.commons.accession.block.initialization.BlockParameters;
import uk.ac.ebi.ampt2d.commons.accession.persistence.jpa.monotonic.service.ContiguousIdBlockService;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Read-only classes for the combination of {@code SpringDataContiguousIdServiceConfiguration} (in
 * accession-commons, enabled via {@code @EnableSpringDataContiguousIdService}) and eva-accession's
 * {@link uk.ac.ebi.eva.accession.core.configuration.ContiguousIdBlocksDataSourceConfiguration}, which together
 * provide the ContiguousIdBlockService bean that {@code SubmittedVariantAccessioningConfiguration} and its
 * siblings depend on.
 *
 * eva-accession-ws only ever reads existing submitted/clustered variants; it never generates new accessions.
 * ContiguousIdBlockService.getBlockParameters(categoryId) (used to compute the ss/rs accessioning thresholds) is a
 * pure in-memory lookup that never touches the database (see ContiguousIdBlockService.java in accession-commons:
 * only reserveNewBlock/save/reserveFirstUncompletedBlockForCategoryIdAndApplicationInstanceId touch the JPA
 * repository, and none of those are on eva-accession-ws's read paths).
 * ContiguousIdBlockService also has a field {@code @PersistenceContext EntityManager entityManager} -
 * Spring's core PersistenceAnnotationBeanPostProcessor injects that field for *any* instance of the class
 * registered as a bean, regardless of how it was constructed or whether the injected EntityManager is ever
 * actually used. That injection needs an EntityManagerFactory bean to exist in the context; without one, the
 * context fails to start even though nothing on eva-accession-ws's read paths would ever touch it. So this
 * configuration provides its own EntityManagerFactory backed by an embedded, in-process HSQLDB instance instead
 * of the original's PostgreSQL DataSource.
 * The method transactionManager serves a similar purpose since ContiguousIdBlockService contains the Transactional annotation
 *
 * {@code @ConditionalOnMissingBean} makes this back off whenever a real, JPA-backed ContiguousIdBlockService is
 * already present in the context (e.g. tests that explicitly import the write-capable core configuration to seed
 * data), so it never shadows a functional bean with this read-only one.
 */
@Configuration
@ConditionalOnMissingBean(name = "contiguousIdBlockService")
public class ReadOnlyContiguousIdBlockServiceConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "accessioning.monotonic")
    public HashMap<String, HashMap<String, String>> contiguousBlockInitializations() {
        return new HashMap<>();
    }

    // An embedded, in-process HSQLDB instance instead of a PostgreSQL DataSource built from continuous.id.blocks.datasource.* properties.
    @Bean
    public DataSource readOnlyContiguousIdBlocksDataSource() {
        return DataSourceBuilder.create()
                                 .driverClassName("org.hsqldb.jdbc.JDBCDriver")
                                 .url("jdbc:hsqldb:mem:eva-accession-ws-contiguous-id-blocks;sql.syntax_pgs=true;DB_CLOSE_DELAY=-1")
                                 .username("SA")
                                 .password("")
                                 .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(readOnlyContiguousIdBlocksDataSource());
        em.setPackagesToScan("uk.ac.ebi.ampt2d.commons.accession.persistence.jpa.monotonic.entities");
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Properties jpaProperties = new Properties();
        jpaProperties.setProperty("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        jpaProperties.setProperty("hibernate.hbm2ddl.auto", "create");
        em.setJpaProperties(jpaProperties);
        return em;
    }

    @Bean
    public PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
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
