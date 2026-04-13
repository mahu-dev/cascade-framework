package cc.coderm.demo.controller;

import cc.coderm.demo.dto.CacheTestDTO;
import cc.coderm.demo.service.TestService;
import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 缓存功能测试Controller
 * 测试多级缓存的各种场景
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:40
 * =============================
 */
@RestController
@RequestMapping("/api/cache/test")
public class CacheTestController {

    private static final Logger log = LoggerFactory.getLogger(CacheTestController.class);

    @Autowired
    private TestService testService;

    @Autowired
    @SuppressWarnings("rawtypes")
    private CacheManager cacheManager;

    /**
     * 测试基础缓存功能
     * 验证：首次请求执行方法，后续请求从缓存读取
     */
    @PostMapping("/basic")
    public String testBasicCache(@RequestBody CacheTestDTO dto) {
        log.info("=== 测试基础缓存功能 ===");
        log.info("请求参数: key={}, value={}", dto.getKey(), dto.getValue());

        String result = testService.testBasicCache(dto);
        log.info("返回结果: {}", result);

        return result;
    }

    /**
     * 测试缓存失效功能
     * 验证：调用此接口后，对应key的缓存被清除，下次请求重新执行方法
     */
    @PostMapping("/evict")
    public String testCacheEvict(@RequestBody CacheTestDTO dto) {
        log.info("=== 测试缓存失效功能 ===");
        log.info("请求参数: key={}", dto.getKey());

        testService.testCacheEvict(dto);
        log.info("缓存已清除: {}", dto.getKey());

        return "缓存清除成功 - key: " + dto.getKey();
    }

    /**
     * 测试缓存更新功能
     * 验证：无论缓存是否存在，都会执行方法并更新缓存
     */
    @PostMapping("/put")
    public String testCachePut(@RequestBody CacheTestDTO dto) {
        log.info("=== 测试缓存更新功能 ===");
        log.info("请求参数: key={}, newValue={}", dto.getKey(), dto.getValue());

        String result = testService.testCachePut(dto);
        log.info("返回结果: {}", result);

        return result;
    }

    /**
     * 测试条件缓存功能
     * 验证：根据enabled参数决定是否缓存
     */
    @PostMapping("/condition")
    public String testConditionCache(@RequestBody CacheTestDTO dto) {
        log.info("=== 测试条件缓存功能 ===");
        log.info("请求参数: key={}, enabled={}", dto.getKey(), dto.getEnabled());

        if (dto.getEnabled() == null) {
            dto.setEnabled(true);
        }

        String result = testService.testConditionCache(dto);
        log.info("返回结果: {}", result);

        return result;
    }

    /**
     * 测试带TTL的缓存功能
     * 验证：缓存有生存时间，过期后自动失效
     */
    @PostMapping("/ttl")
    public String testCacheWithTTL(@RequestBody CacheTestDTO dto) {
        log.info("=== 测试带TTL的缓存功能 ===");
        log.info("请求参数: key={}, value={}", dto.getKey(), dto.getValue());

        String result = testService.testCacheWithTTL(dto);
        log.info("返回结果: {}", result);
        log.info("提示：缓存TTL为10秒，10秒后再次请求将重新执行方法");

        return result;
    }

    /**
     * 测试编程式缓存使用
     * 验证：直接使用Cache API进行缓存操作
     */
    @PostMapping("/programmatic")
    public String testProgrammaticCache(@RequestBody CacheTestDTO dto) {
        log.info("=== 测试编程式缓存 ===");
        log.info("请求参数: key={}, value={}", dto.getKey(), dto.getValue());

        Cache<String, String> cache = cacheManager.getOrCreateCache(
                "programmatic-cache",
                String.class,
                String.class
        );

        String cachedValue = cache.getOrLoad(dto.getKey(), key -> {
            log.info(">>> 缓存未命中，执行加载逻辑，key: {}", key);
            return "Programmatic:" + dto.getValue();
        });

        log.info("返回结果: {}", cachedValue);
        return cachedValue;
    }

    /**
     * 查询缓存状态
     * 验证：检查缓存中是否存在指定key
     */
    @PostMapping("/status")
    public String checkCacheStatus(@RequestBody CacheTestDTO dto) {
        log.info("=== 查询缓存状态 ===");
        log.info("请求参数: key={}", dto.getKey());

        Cache<String, String> cache = cacheManager.getCache("test:cache");
        if (cache == null) {
            return "缓存不存在";
        }

        Optional<String> value = cache.get(dto.getKey());
        if (value.isPresent()) {
            log.info("缓存命中: key={}, value={}", dto.getKey(), value.get());
            return "缓存命中 - key: " + dto.getKey() + ", value: " + value.get();
        } else {
            log.info("缓存未命中: key={}", dto.getKey());
            return "缓存未命中 - key: " + dto.getKey();
        }
    }

    /**
     * 清除所有缓存
     * 验证：清空指定缓存名称下的所有数据
     */
    @PostMapping("/clear")
    public String clearAllCache() {
        log.info("=== 清除所有缓存 ===");

        Cache<String, String> cache = cacheManager.getCache("test:cache");
        if (cache != null) {
            cache.clear();
            log.info("缓存已清空: test:cache");
            return "缓存清空成功";
        }

        return "缓存不存在，无需清空";
    }
}
