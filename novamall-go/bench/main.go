// Package main 是一个轻量级 HTTP 压测工具。
//
// 为什么自己写一个而不用 JMeter / wrk？
//  1. 零依赖：`go run .` 就能跑，不用装 Java、不用编译 wrk
//  2. 可读：300 行代码把"并发模型 + 指标统计"讲得清清楚楚，面试可以直接讲
//  3. 可定制：想加"按 QPS 匀速发压""打印错误分布"随时改
//
// 它同时也是这个仓库里 Go 语言的"出场作品"：
// goroutine + channel + WaitGroup 是 Go 并发的三件套，这里全用上了。
//
// 用法：
//
//	go run . -url http://127.0.0.1:8080/api/product/10001 -c 200 -n 10000
//	go run . -url http://127.0.0.1:8084/api/seckill/1/10001 -c 500 -d 30s -X POST
package main

import (
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// result 汇总一次压测的结果
type result struct {
	total     int64
	success   int64
	failed    int64
	latencies []time.Duration
	statusSet map[int]int64
	mu        sync.Mutex
}

// record 记录单次请求。用互斥锁保护共享切片——
// 也可以用 sync.Pool 或者每个 worker 先攒自己那份最后再合并（锁竞争更小），
// 这里为了代码清晰选择了加锁。
func (r *result) record(ok bool, status int, d time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	atomic.AddInt64(&r.total, 1)
	if ok {
		atomic.AddInt64(&r.success, 1)
	} else {
		atomic.AddInt64(&r.failed, 1)
	}
	r.statusSet[status]++
	r.latencies = append(r.latencies, d)
}

// percentile 计算分位数。P99 比平均值重要得多：
// 平均值会被大量快请求拉低，掩盖掉那 1% 慢得离谱的请求，
// 而用户体验恰恰是被这 1% 决定的。
func (r *result) percentile(p float64) time.Duration {
	if len(r.latencies) == 0 {
		return 0
	}
	sorted := make([]time.Duration, len(r.latencies))
	copy(sorted, r.latencies)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	idx := int(float64(len(sorted)) * p)
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func (r *result) avg() time.Duration {
	if len(r.latencies) == 0 {
		return 0
	}
	var sum time.Duration
	for _, d := range r.latencies {
		sum += d
	}
	return sum / time.Duration(len(r.latencies))
}

func main() {
	url := flag.String("url", "", "目标 URL（必填）")
	concurrency := flag.Int("c", 100, "并发数（同时有多少个 goroutine 在发请求）")
	total := flag.Int("n", 1000, "总请求数")
	duration := flag.Duration("d", 0, "持续时长，如 30s；指定后忽略 -n")
	method := flag.String("X", "GET", "HTTP 方法")
	timeout := flag.Duration("timeout", 10*time.Second, "单请求超时")
	header := flag.String("H", "", "请求头，格式 'Authorization: Bearer xxx'")
	flag.Parse()

	if *url == "" {
		flag.Usage()
		os.Exit(1)
	}

	res := &result{statusSet: make(map[int]int64)}

	// 复用连接：默认的 DefaultTransport 最大空闲连接数比较小，
	// 压测时不调大就会出现大量 TIME_WAIT，测出来的是"建连接的性能"而不是"接口性能"。
	transport := &http.Transport{
		MaxIdleConns:        *concurrency * 2,
		MaxIdleConnsPerHost: *concurrency * 2,
		IdleConnTimeout:     90 * time.Second,
	}
	client := &http.Client{Transport: transport, Timeout: *timeout}

	var headerKey, headerValue string
	if *header != "" {
		for i := 0; i < len(*header); i++ {
			if (*header)[i] == ':' {
				headerKey = (*header)[:i]
				if i+2 <= len(*header) {
					headerValue = (*header)[i+2:]
				}
				break
			}
		}
	}

	// jobs 是任务通道：主协程往里塞任务，worker 协程抢着干。
	// 这种"生产者-消费者"模型是 Go 里最常用的并发模式，
	// 天然起到了限流作用——不管有多少任务，同时只有 c 个在跑。
	jobs := make(chan struct{})
	var wg sync.WaitGroup

	deadline := time.Now().Add(*duration)
	useDuration := *duration > 0

	start := time.Now()

	for i := 0; i < *concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range jobs {
				if useDuration && time.Now().After(deadline) {
					return
				}
				doRequest(client, *url, *method, headerKey, headerValue, res)
			}
		}()
	}

	if useDuration {
		// 定时模式：一直喂任务直到时间到
		go func() {
			for {
				if time.Now().After(deadline) {
					close(jobs)
					return
				}
				jobs <- struct{}{}
			}
		}()
		wg.Wait()
	} else {
		for i := 0; i < *total; i++ {
			jobs <- struct{}{}
		}
		close(jobs)
		wg.Wait()
	}

	elapsed := time.Since(start)
	printReport(*url, *concurrency, res, elapsed)
}

func doRequest(client *http.Client, url, method, hk, hv string, res *result) {
	req, err := http.NewRequest(method, url, nil)
	if err != nil {
		res.record(false, 0, 0)
		return
	}
	if hk != "" {
		req.Header.Set(hk, hv)
	}

	begin := time.Now()
	resp, err := client.Do(req)
	cost := time.Since(begin)

	if err != nil {
		res.record(false, 0, cost)
		return
	}
	defer resp.Body.Close()
	// 必须把响应体读掉并关闭，否则连接无法复用回连接池，
	// 跑一会儿就会出现 "too many open files"
	_, _ = io.Copy(io.Discard, resp.Body)

	res.record(resp.StatusCode < 400, resp.StatusCode, cost)
}

func printReport(url string, concurrency int, res *result, elapsed time.Duration) {
	fmt.Println()
	fmt.Println("========== 压测报告 ==========")
	fmt.Printf("目标地址   : %s\n", url)
	fmt.Printf("并发数     : %d\n", concurrency)
	fmt.Printf("总耗时     : %v\n", elapsed.Round(time.Millisecond))
	fmt.Printf("总请求数   : %d\n", res.total)
	fmt.Printf("成功/失败  : %d / %d\n", res.success, res.failed)
	if elapsed.Seconds() > 0 {
		fmt.Printf("QPS        : %.2f\n", float64(res.total)/elapsed.Seconds())
	}
	fmt.Printf("平均耗时   : %v\n", res.avg().Round(time.Microsecond))
	fmt.Printf("P50        : %v\n", res.percentile(0.50).Round(time.Microsecond))
	fmt.Printf("P95        : %v\n", res.percentile(0.95).Round(time.Microsecond))
	fmt.Printf("P99        : %v\n", res.percentile(0.99).Round(time.Microsecond))
	fmt.Println("状态码分布 :")
	for code, count := range res.statusSet {
		fmt.Printf("   %d : %d\n", code, count)
	}
	fmt.Println("==============================")
	fmt.Println()
	fmt.Println("怎么看这份报告：")
	fmt.Println("  1. QPS 和 P99 一起看。只报 QPS 高没意义，P99 才是用户真实感受")
	fmt.Println("  2. P99 远大于 P50，说明有长尾请求，通常是 GC、锁竞争、慢 SQL 或连接池打满")
	fmt.Println("  3. 加大并发后 QPS 不再增长甚至下降，说明已经到瓶颈了")
}
