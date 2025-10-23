// 예시: Configuration 클래스
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.atomic.AtomicReference;
/*
 * 트롤러와 서비스 간 activeTaskId 공유
컨트롤러와 서비스에서 동일한 activeTaskId 인스턴스를 사용해야 합니다. 이는 @Configuration 클래스에 @Bean으로 등록하여 싱글톤으로 관리하거나, 컨트롤러에서 서비스를 생성할 때 activeTaskId를 주입하는 방식으로 해결할 수 있습니다.
가장 간단한 방법은 AtomicReference를 @Component로 등록하여 스프링 빈으로 관리하는 것입니다.
 * 
 */
@Configuration
public class AppConfig {
    @Bean
    public AtomicReference<String> activeTaskId() {
        return new AtomicReference<>(null);
    }
}
