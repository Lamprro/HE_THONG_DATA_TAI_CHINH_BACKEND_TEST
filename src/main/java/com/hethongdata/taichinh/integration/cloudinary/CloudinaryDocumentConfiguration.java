package com.hethongdata.taichinh.integration.cloudinary;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudinaryDocumentProperties.class)
public class CloudinaryDocumentConfiguration {}
