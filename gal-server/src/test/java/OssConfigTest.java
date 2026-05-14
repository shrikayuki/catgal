import com.catgal.server.CatgalApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;


@SpringBootTest(classes = CatgalApplication.class)  // 加这一行
public class OssConfigTest {

    @Value("${aliyun.oss.endpoint:}")
    private String endpoint;

    @Value("${aliyun.oss.bucket-name:}")
    private String bucketName;

    @Value("${aliyun.oss.access-key-id:}")
    private String accessKeyId;

    @Value("OMyDpdd8WsV9XoZnbpPm2YnO1foIQ6")
    String accessKeySecret;


    @Test
    void testConfig() {
        System.out.println("========== OSS配置校验 ==========");
        System.out.println("endpoint: " + endpoint);
        System.out.println("bucketName: " + bucketName);
        System.out.println("accessKeyId: " + accessKeyId);
        
        // 检查是否为空
        if (endpoint.isEmpty() || bucketName.isEmpty() || accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
            System.out.println("❌ 配置有误，有值为空！");
        } else {
            System.out.println("✅ 配置全部读取成功");
        }
    }
}