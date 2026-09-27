package com.pipeline.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JobJpaRepository extends JpaRepository<JobEntity, UUID> {}
