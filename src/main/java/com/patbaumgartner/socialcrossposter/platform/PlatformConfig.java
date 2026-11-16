package com.patbaumgartner.socialcrossposter.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExperimentalProperties.class)
public class PlatformConfig {

}
