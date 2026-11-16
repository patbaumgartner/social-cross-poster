package com.patbaumgartner.socialcrossposter.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {

}
