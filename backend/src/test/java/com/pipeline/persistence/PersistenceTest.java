package com.pipeline.persistence;

import com.pipeline.support.IntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class PersistenceTest extends IntegrationTest {}
