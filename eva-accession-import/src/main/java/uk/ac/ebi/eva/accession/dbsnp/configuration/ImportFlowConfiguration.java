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
package uk.ac.ebi.eva.accession.dbsnp.configuration;

import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static uk.ac.ebi.eva.accession.dbsnp.configuration.BeanNames.FORCE_IMPORT_DECIDER;
import static uk.ac.ebi.eva.accession.dbsnp.configuration.BeanNames.IMPORT_DBSNP_VARIANTS_STEP;
import static uk.ac.ebi.eva.accession.dbsnp.configuration.BeanNames.IMPORT_DBSNP_VARIANTS_FLOW_WITH_DECIDER;
import static uk.ac.ebi.eva.accession.dbsnp.configuration.BeanNames.VALIDATE_CONTIGS_STEP;

@Configuration
public class ImportFlowConfiguration {

    @Autowired
    @Qualifier(IMPORT_DBSNP_VARIANTS_STEP)
    private Step importDbsnpVariantsStep;

    @Autowired
    @Qualifier(VALIDATE_CONTIGS_STEP)
    private Step validateContigsStep;

    @Autowired
    @Qualifier(FORCE_IMPORT_DECIDER)
    private JobExecutionDecider decider;

    @Bean(IMPORT_DBSNP_VARIANTS_FLOW_WITH_DECIDER)
    public Flow optionalFlow() {
        return new FlowBuilder<Flow>("OPTIONAL_FLOW")
                .start(decider).on("TRUE")
                .to(importDbsnpVariantsStep)
                .from(decider).on("FALSE")
                .to(validateContigsStep)
                .next(importDbsnpVariantsStep)
                .build();
    }
}
