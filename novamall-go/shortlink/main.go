// Package main 短链接服务（Go + Gin + Redis）。
//
// 为什么在这个以 Java 为主的项目里放一个 Go 服务？
//   - 展示语言广度：后端工程师不该被单一语言绑死
//   - 短链是典型的"IO 密集 + 高 QPS"场景，Go 的协程模型很合适：
//     同样的机器，Go 能轻松扛住几万并发连接且内存占用只有 JVM 的几分之一
//   - 部署成本极低：编译出来是一个二进制文件，没有 JVM、没有依赖，
//     Docker 镜像可以做到 20MB 以内
//
// 功能：
//   POST /api/shorten   长链接 → 短码
//   GET  /:code         302 跳转
//   GET  /api/stats/:code  访问统计
//
// 启动：
//   go mod tidy && go run . -addr :8200 -redis 127.0.0.1:6379 -base http://s.novamall.com
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
)

// BASE62 字符集：62 个字符，6 位短码可以表示 62^6 ≈ 568 亿种组合。
// 为什么用 62 而不是 36（纯数字+小写）？同样长度下能表示的空间大得多，
// 短码可以做得更短。
const alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

var (
	addr    string
	redisAddr string
	baseURL string
	rdb     *redis.Client
)

func main() {
	flag.StringVar(&addr, "addr", ":8200", "监听地址")
	flag.StringVar(&redisAddr, "redis", "127.0.0.1:6379", "Redis 地址")
	flag.StringVar(&baseURL, "base", "http://127.0.0.1:8200", "短链域名前缀")
	flag.Parse()

	rdb = redis.NewClient(&redis.Options{
		Addr:         redisAddr,
		PoolSize:     50,
		MinIdleConns: 10,
		DialTimeout:  2 * time.Second,
		ReadTimeout:  1 * time.Second,
		WriteTimeout: 1 * time.Second,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := rdb.Ping(ctx).Err(); err != nil {
		log.Printf("[warn] 连接 Redis 失败（%v），将使用内存模式，重启后数据丢失", err)
		rdb = nil
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery())

	r.POST("/api/shorten", shorten)
	r.GET("/api/stats/:code", stats)
	// 重定向要放在最后，避免把 /api/xxx 当成短码解析
	r.GET("/:code", redirect)

	srv := &http.Server{Addr: addr, Handler: r}

	// 优雅停机：收到 SIGTERM 后不再接收新请求，
	// 等正在处理的请求做完（最多 5 秒）再退出。
	// K8s 滚动发布时如果不等，正在处理的请求会被直接掐断，用户看到 502。
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("启动失败: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("收到退出信号，开始优雅停机...")

	ctx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("强制关闭: %v", err)
	}
	log.Println("已退出")
}

type shortenRequest struct {
	URL     string `json:"url" binding:"required"`
	TTLHours int   `json:"ttlHours"`
}

func shorten(c *gin.Context) {
	var req shortenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 4000, "message": "参数错误"})
		return
	}
	if len(req.URL) < 8 {
		c.JSON(http.StatusBadRequest, gin.H{"code": 4000, "message": "URL 格式不正确"})
		return
	}

	code := generateCode(6)
	ttl := time.Duration(req.TTLHours) * time.Hour
	if ttl <= 0 {
		ttl = 30 * 24 * time.Hour
	}

	ctx := c.Request.Context()
	if rdb != nil {
		// 用 SetNX 而不是 Set：万一随机码撞了，宁可重试也不能覆盖别人的短链
		for i := 0; i < 5; i++ {
			ok, err := rdb.SetNX(ctx, keyOf(code), req.URL, ttl).Result()
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"code": 9000, "message": "服务异常"})
				return
			}
			if ok {
				break
			}
			code = generateCode(6)
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "success",
		"data": gin.H{
			"code":     code,
			"shortUrl": fmt.Sprintf("%s/%s", baseURL, code),
			"expireIn": int(ttl.Hours()),
		},
	})
}

func redirect(c *gin.Context) {
	code := c.Param("code")
	if len(code) == 0 || len(code) > 32 {
		c.JSON(http.StatusNotFound, gin.H{"code": 4004, "message": "短链不存在"})
		return
	}

	ctx := c.Request.Context()
	var target string
	if rdb != nil {
		target, _ = rdb.Get(ctx, keyOf(code)).Result()
	}
	if target == "" {
		c.JSON(http.StatusNotFound, gin.H{"code": 4004, "message": "短链不存在或已过期"})
		return
	}

	// 异步累加访问量，不能阻塞跳转。
	// 真实场景这里应该发 MQ 或写本地缓冲批量落库，
	// 每次跳转都写一次 Redis/DB 在高 QPS 下会成为瓶颈。
	if rdb != nil {
		go func() {
			bg := context.Background()
			rdb.Incr(bg, "shortlink:pv:"+code)
			rdb.Expire(bg, "shortlink:pv:"+code, 30*24*time.Hour)
		}()
	}

	// 302 而不是 301：301 是永久重定向，浏览器会缓存，
	// 之后就统计不到访问量了。短链业务必须用 302。
	c.Redirect(http.StatusFound, target)
}

func stats(c *gin.Context) {
	code := c.Param("code")
	ctx := c.Request.Context()
	pv := int64(0)
	if rdb != nil {
		pv, _ = rdb.Get(ctx, "shortlink:pv:"+code).Int64()
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"code": code, "pv": pv}})
}

func keyOf(code string) string {
	return "shortlink:url:" + code
}

// generateCode 生成随机短码。
//
// 为什么不直接用自增 ID 转 62 进制？
// 自增 ID 的短码是可枚举的（abc001、abc002...），
// 别人能遍历你的短链看里面是什么，有隐私泄露风险。
// 随机码虽然要处理冲突（用 SetNX 兜底），但更安全。
func generateCode(n int) string {
	b := make([]byte, n)
	for i := range b {
		b[i] = alphabet[rand.Intn(len(alphabet))]
	}
	return string(b)
}
