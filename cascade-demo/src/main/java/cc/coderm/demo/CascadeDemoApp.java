package cc.coderm.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/8/14
 * Time: 11:22
 * =============================
 */
@SpringBootApplication
@EnableCaching
public class CascadeDemoApp {

    public static void main(String[] args) {
        SpringApplication.run(CascadeDemoApp.class, args);
    }
}
