package com.bank.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ai-service 는 RDB(JPA) 비사용. spring-data-jpa starter 는 build.gradle 에서 제외하고,
 * common 의 JPA 의존 패키지(audit/persistence/code)는 스캔에서 제외 — JpaRepository 클래스가
 * 클래스패스에 없어서 로드 시 NoClassDefFoundError 가 나기 때문. 필요한 건 common.web 뿐.
 */
@SpringBootApplication(scanBasePackages = {"com.bank.ai", "com.bank.common.web"})
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
