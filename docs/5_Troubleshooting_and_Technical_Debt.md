# 5. Troubleshooting & Technical Debt

Trong quá trình bảo trì và phát triển dự án Agentic Orchestrator, team có thể gặp phải một số lỗi phổ biến hoặc các phần "nợ kỹ thuật" (Technical Debt) chưa được khắc phục triệt để. Tài liệu này cung cấp cách gỡ lỗi nhanh.

## 5.1 Các lỗi thường gặp (FAQ / Troubleshooting)

### Lỗi 1: `dependency failed to start: container mcp-puppeteer is unhealthy` khi chạy Docker Compose
- **Hiện tượng**: Chạy `docker-compose up -d`, các container khác chạy bình thường nhưng `app` (Spring Boot) không khởi động được do phụ thuộc (depends_on) vào `mcp-puppeteer` khỏe mạnh.
- **Nguyên nhân**: `mcp-puppeteer` dùng thư viện Node để cài đặt Puppeteer và Chromium. Đôi khi quá trình nạp proxy nội bộ tốn hơn 60s (quá thời gian Healthcheck cho phép).
- **Cách khắc phục nhanh**: Chạy ép khởi động app bằng lệnh `docker-compose up -d --no-deps app` (Không quan tâm dependencies).
- **Cách khắc phục triệt để**: Sửa file `docker-compose.yml`, tăng `timeout` và `start_period` của service `mcp-puppeteer` lên trên 60s.

### Lỗi 2: Ứng dụng liên tục báo `java.net.ConnectException`
- **Hiện tượng**: Log console báo lỗi không thể kết nối tới MCP Server như `Failed to initialize MCP Client [postgres] at http://localhost:8081/sse` mặc dù container PostgreSQL vẫn đang chạy khỏe.
- **Nguyên nhân**: Sự nhập nhằng cấu hình giữa `localhost` (khi chạy app ở môi trường Dev / host) và service name (khi chạy trong Docker). Khi chạy bằng docker-compose, `localhost` chỉ trỏ tới chính container Spring App, chứ không trỏ đến các container MCP. Do mã nguồn hiện tại đang có dấu hiệu hardcode fallback về `localhost:8081`, `localhost:8082`, v.v.
- **Cách khắc phục**: Cập nhật lại file `application.yml` hoặc các class `McpClientConfig` trong Java để chắc chắn Spring ưu tiên sử dụng biến môi trường (VD: `${MCP_POSTGRES_URL:http://mcp-postgres:8081/sse}`) thay vì fallback tĩnh về `localhost`. Tạm thời có thể test tính năng bằng cách chạy trực tiếp ứng dụng Java trên máy thật (`./mvnw spring-boot:run`).

### Lỗi 3: Lập kế hoạch (Plan) thất bại hoặc báo Null
- **Hiện tượng**: Trả về lỗi `"Received null or empty plan!"` hoặc `DAG SCHEDULER DEADLOCK`.
- **Nguyên nhân**: LLM trả về cấu trúc JSON sai lệch (Hallucination) hoặc bị Rate Limit từ phía API cung cấp.
- **Cách khắc phục**: Đảm bảo các API Key trong `.env` còn hạn và có quota. Nếu vẫn lỗi, chỉnh lại prompt template trong `TaskRouter` hoặc yêu cầu người dùng sử dụng câu hỏi (goal) đơn giản, mạch lạc hơn.

---

## 5.2 Nợ kỹ thuật (Technical Debt) cần giải quyết

> [!WARNING]
> Đây là những hạng mục cần Team đưa vào Sprint Backlog để ưu tiên Refactor nhằm đảm bảo hệ thống vận hành bền vững trên Production.

1. **Vấn đề Hardcode URL MCP**: Như đã đề cập ở Lỗi 2, việc cấu hình tĩnh các địa chỉ kết nối nội bộ gây rắc rối trong môi trường Container orchestration (Kubernetes / Docker Swarm).
2. **Quản lý Vòng đời Sandbox (Sandbox Lifecycle)**: Container sinh ra để chạy script Python/JS (`DockerManagedSandbox`) tuy là tài nguyên dùng 1 lần (Ephemeral) nhưng đôi khi vẫn có thể bỏ sót việc xóa dọn (Cleanup) nếu tiến trình Java bị crash đột ngột. Cần bổ sung cơ chế theo dõi (Reaper daemon) dọn rác định kỳ.
3. **Thiếu cơ chế Authorization mạnh cho UI**: Dashboard HTMX hiện tại đang ở trạng thái mở. Bất kỳ ai vào port 8080 cũng có thể gửi lệnh điều khiển. Cần cấu hình Spring Security cơ bản.
4. **Log quá ồn (Noisy Logs)**: Lỗi từ Spring AI bị Exception sẽ in ra màn hình toàn bộ Stacktrace. Cần có cơ chế bắt Exception tốt hơn (`@ControllerAdvice` hoặc tuỳ biến Logger) để in ra các thông điệp dễ đọc hơn cho DevOps.

## 5.3 Lời khuyên cho Developer mới
- Luôn chạy ứng dụng ở chế độ **Debug mode** trong IntelliJ.
- Đặt breakpoint tại `TaskRouter.java` (để hiểu cách phân luồng prompt) và `ExecutionEngine.java` (để thấy cách lập lịch DAG).
- Hãy tham khảo `AGENT_GUIDE.md` để lấy danh sách các kịch bản test chuẩn (VD: ReAct, Pivot, Suspended).
