// SPDX-License-Identifier: Apache-2.0
package com.syntaric.federation;

import com.syntaric.federation.config.FederationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FederationProperties.class)
public class FederationGatewayApplication {

    public static void main(final String[] args) {
        SpringApplication.run(FederationGatewayApplication.class, args);
    }
}
