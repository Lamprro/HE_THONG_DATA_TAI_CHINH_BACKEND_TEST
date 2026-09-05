package com.hethongdata.taichinh.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling; individual scheduler components define triggers and opt-in properties.
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {}
