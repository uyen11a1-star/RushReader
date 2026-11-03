# Rush Reader (v0.1.0)
Trình đọc tài liệu Android native, offline, tối ưu RAM thấp. Kotlin + Jetpack Compose + PdfRenderer (PDFium của Android).

## Build APK (GitHub Actions, không cần PC)
```
git init && git add . && git commit -m "Rush Reader v0.1"
git branch -M main
git remote add origin https://github.com/uyen11a1-star/RushReader.git
git push -u origin main
```
Vào tab Actions → job "Build APK" → tải artifact `RushReader-release` (app-release.apk, ký bằng debug key).

## Cấu trúc
- core/: nhận dạng định dạng (đuôi + MIME + magic bytes), intent, cache tạm
- data/: lưu gần đây/yêu thích/cài đặt, duyệt thư mục (SAF)
- pdf/: PdfEngine (1 thread render, tuần tự), BitmapPool, RenderPolicy (ngân sách bitmap theo RAM)
- docx/: DocxParser (streaming XmlPullParser), TxtLoader
- ui/: Home, Duyệt file, Reader, Yêu thích, Cài đặt

## Giới hạn hiện tại
Xem mục "Định dạng hỗ trợ" trong màn Cài đặt. Chưa build/test trên thiết bị.
