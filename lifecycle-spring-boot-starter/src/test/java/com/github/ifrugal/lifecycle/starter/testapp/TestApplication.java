package com.github.ifrugal.lifecycle.starter.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The minimal application every slice test boots. It lives in its own package on purpose: component scanning
 * from the starter's own package would pick the {@code @AutoConfiguration} classes up a second time, as plain
 * {@code @Configuration}, and their ordering annotations would stop meaning anything.
 */
@SpringBootApplication
public class TestApplication {
}
