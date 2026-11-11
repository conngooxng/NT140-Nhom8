# README

Repo này chứa code cho đồ án An toàn mạng: "An Analysis of the Privacy and
Security Risks of Android VPN Permission-enabled Apps".

## Cấu trúc:
- `server/`: một số đoạn script dùng để khởi tạo network, server và proxy
- `victim-lab/`: một app để gọi một số endpoint của server
- `vpn-lab/`: một app kết nối tới proxy qua VPN

## Chạy

### 1. Khởi tạo network, server và proxy

- Bước 1: Cài các gói `hostapd dnsmasq mitmproxy tcpdump python3 openssl`
- Bước 2: Khởi tạo network. Interface mặc định là `wlan0`, có thể thay đổi nếu cần:

```bash
$ sudo ./server/network.sh up [interface]
```

- Bước 3: Sinh cert và khởi tạo server

```bash
$ ./server/generate_certs.sh
$ ./server/run_server.sh start
```

- Bước 4: Khởi tạo proxy

```bash
$ ./server/run_proxy.sh start
```

### 2. Kết nối điện thoại tới mạng

- Kết nối điện thoại Android tới mạng `VPN-LAB` được tạo ở 1.3., mật khẩu mặc định là `labpass12345`.
- Tắt 4G/5G và DNS private trên thiết bị (nếu có).

### Kết thúc

Sau khi xong việc, tắt mạng `VPN-LAB` bằng lệnh bên dưới để máy có thể kết nối mạng bình thường:

```bash
$ sudo ./server/network.sh cleanup [interface]
```
