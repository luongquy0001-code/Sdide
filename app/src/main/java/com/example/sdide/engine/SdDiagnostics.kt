package com.example.sdide.engine

enum class DiagnosticSeverity { ERROR, WARNING }

data class SdDiagnostic(
  val code: String,
  val phase: Int,
  val line: Int,
  val col: Int = 1,
  val fileName: String? = null,
  val message: String,
  val hint: String? = null,
  val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
) {
  fun format(): String {
    val loc = buildString {
      if (!fileName.isNullOrBlank()) {
        append("@$fileName ")
      } else {
        append("@")
      }
      append("dong $line")
      if (col > 1) append(" cot $col")
    }
    val prefix = if (severity == DiagnosticSeverity.ERROR) "LOI" else "CANH BAO"
    return buildString {
      append("[$prefix $code $loc] $message")
      if (!hint.isNullOrBlank()) {
        append("\n  goi y: $hint")
      }
    }
  }
}

class SdCompileException(val diagnostic: SdDiagnostic) : RuntimeException(diagnostic.format())

class SdRuntimeException(val diagnostic: SdDiagnostic) : RuntimeException(diagnostic.format())

data class ErrorCatalogEntry(
  val code: String,
  val phase: String,
  val title: String,
  val detail: String,
  val chapter: String,
)

object SdErrorCatalog {
  val entries: List<ErrorCatalogEntry> = listOf(
    ErrorCatalogEntry("E100", "Pha 1: Đọc", "Lỗi đọc không xác định", "Mã mặc định cho lỗi ở pha đọc.", "19"),
    ErrorCatalogEntry("E101", "Pha 1: Đọc", "Ghi chú chưa đóng", "Ghi chú \\\\ ... \\\\ hoặc \\\\* ... *\\\\ mở mà chưa đóng trước cuối tệp.", "1, 16, 22"),
    ErrorCatalogEntry("E102", "Pha 1: Đọc", "Ký tự không thuộc ngôn ngữ", "Xuất hiện ký tự lạ hoặc dấu ; bên trong một ngăn.", "16, 23"),
    ErrorCatalogEntry("E103", "Pha 1: Đọc", "Chuỗi chưa đóng nháy kép", "Chuỗi thiếu \" đóng hoặc chứa xuống dòng vật lý trong luồng phẳng.", "16, 22"),
    ErrorCatalogEntry("E104", "Pha 1: Đọc", "Cụm // không hợp lệ trong chuỗi", "Trong chuỗi, // theo sau bởi chữ cái khác 'e'. Dùng //e để xuống dòng.", "22"),
    ErrorCatalogEntry("E200", "Pha 2: Cú pháp", "Lỗi cú pháp không xác định", "Mã mặc định cho lỗi ở pha phân tích cú pháp.", "19"),
    ErrorCatalogEntry("E201", "Pha 2: Cú pháp", "Thiếu chốt hoặc thành phần lệnh", "Thiếu ed hoặc cờ chốt ($, ₫, €, #) ở cuối câu lệnh.", "9, 14"),
    ErrorCatalogEntry("E202", "Pha 2: Cú pháp", "Lệnh lạ hoặc khối chưa khép", "Lệnh không nhận dạng, thiếu chốt khối (bln/dbl/strt/void/fn ed) hoặc khép sai loại.", "4, 16"),
    ErrorCatalogEntry("E203", "Pha 2: Cú pháp", "Thiếu hoặc thừa dấu }", "Cặp ngoặc nhọn { } mở và đóng không khớp.", "9"),
    ErrorCatalogEntry("E204", "Pha 2: Cú pháp", "Thiếu main hoặc thiếu ref*", "Chương trình thiếu khung main hoặc khung main không kết thúc bằng ref*.", "1, 9"),
    ErrorCatalogEntry("E205", "Pha 2: Cú pháp", "Dùng thư viện chưa imp*", "Gọi pri/ipt hoặc hàm thư viện chuẩn (math, str, file, mem, sys, draw) khi chưa nạp.", "15"),
    ErrorCatalogEntry("E206", "Pha 2: Cú pháp", "Đầu vòng for thiếu mục", "Vòng for bắt buộc có đủ 3 mục ngăn bằng dấu nháy đơn ': khởi tạo ' điều kiện ' bước.", "5"),
    ErrorCatalogEntry("E207", "Pha 2: Cú pháp", "Hàm thiếu ref* cuối cùng", "Hàm fn có kiểu trả về nhưng câu lệnh cuối ở cấp ngoài cùng không phải ref*.", "7"),
    ErrorCatalogEntry("E208", "Pha 2: Cú pháp", "Sd++ thiếu ssta hoặc ##", "Chương trình Sd++ thiếu nắp mở ssta { hoặc nắp đóng ## ở cuối (hoặc cuối ngăn .sdall).", "10, 23"),
    ErrorCatalogEntry("E209", "Pha 2: Cú pháp", "Định nghĩa lại cờ dành riêng", "Khối defk cố định nghĩa lại $, ₫, €, $$, #, ## hoặc cờ đã niêm phong.", "14"),
    ErrorCatalogEntry("E210", "Pha 2: Cú pháp", "Khối defk thiếu # niêm phong", "Mọi khối defk phải được niêm phong bằng cờ # trước khi đóng }.", "14"),
    ErrorCatalogEntry("E211", "Pha 2: Cú pháp", "Nạp tệp vòng tròn", "Hai hoặc nhiều tệp imp* lẫn nhau tạo vòng lặp vô hạn.", "15"),
    ErrorCatalogEntry("E213", "Pha 2: Cú pháp", "Lỗi :& hoặc *m lồng nhau", "Đặt ranh giới :& bên trong khối con hoặc mở *m khi vùng *m trước chưa đóng.", "14"),
    ErrorCatalogEntry("E214", "Pha 2: Cú pháp", "Trộn main và lệnh viết thẳng", "Trong ssta vừa có khung main vừa có lệnh viết thẳng cấp ngoài.", "14"),
    ErrorCatalogEntry("E215", "Pha 2: Cú pháp", "Không tìm thấy tệp imp*", "Tệp thư viện người dùng được nạp qua imp* không tồn tại trong dự án.", "15"),
    ErrorCatalogEntry("E216", "Pha 2: Cú pháp", "Khối #{ hoặc #sdbin không đóng", "Khối gọi thanh ghi thiếu }# hoặc thiếu # đóng #sdbin:.", "21"),
    ErrorCatalogEntry("E217", "Pha 2: Cú pháp", "Tên thanh ghi không hợp lệ", "Thanh ghi hợp lệ chỉ gồm 0Ax, 0Bx, 0Cx, 0Dx.", "21"),
    ErrorCatalogEntry("E218", "Pha 2: Cú pháp/Ngữ nghĩa", "*m(x) với biến thường", "Toán tử *m(x) chỉ dùng với biến tham chiếu mượn khai báo bằng tên:&.", "21"),
    ErrorCatalogEntry("E219", "Pha 2: Cú pháp", "Hai cột mốc impt*AaC chồng nhau", "Đặt impt*AaC mới khi một vùng AaC trước đó vẫn còn hiệu lực.", "21"),
    ErrorCatalogEntry("E220", "Pha 2: Ngữ nghĩa", "Thanh ghi chưa nx{ } trước ##", "Thanh ghi đã nạp bằng #sdbin: nhưng chưa được triệt tiêu bằng :p ... nx{ } trước ##.", "21"),
    ErrorCatalogEntry("E221", "Pha 2: Cú pháp", "Quá 4 thanh ghi trong một lệnh", "Một lệnh chỉ được dùng tối đa bốn thanh ghi mô phỏng cùng lúc.", "21"),
    ErrorCatalogEntry("E222", "Pha 2: Cú pháp", "Công tắc .sdall sai hoặc sai chỗ", "Công tắc phải là start, on, pause, off, stop; đặt ngay trước ngăn, mỗi ngăn tối đa 1 công tắc.", "23"),
    ErrorCatalogEntry("E301", "Pha 3: Ngữ nghĩa", "Tên chưa khai báo", "Biến, hàm, kiểu struct hoặc trường dữ liệu chưa được khai báo hoặc ngoài phạm vi.", "2, 8"),
    ErrorCatalogEntry("E302", "Pha 3: Ngữ nghĩa", "Trùng tên", "Khai báo trùng biến cùng khung, trùng trường struct, trùng tên toàn cục hoặc gọi hàm trùng tên ngắn.", "2, 8, 11, 15"),
    ErrorCatalogEntry("E303", "Pha 3: Ngữ nghĩa", "Sai kiểu dữ liệu", "Gán/trả về sai kiểu, dùng = trong điều kiện, hoặc điều kiện không phải bool.", "2, 19"),
    ErrorCatalogEntry("E304", "Pha 3: Ngữ nghĩa", "Sai số đối số", "Số lượng đối số khi gọi hàm không khớp với định nghĩa.", "7, 19"),
    ErrorCatalogEntry("E305", "Pha 3: Ngữ nghĩa", "ref*/brk/nxt/transf sai chỗ", "Lệnh nhảy hoặc thoát đặt ngoài khung hợp lệ tương ứng.", "5, 7, 13"),
    ErrorCatalogEntry("E306", "Pha 3: Ngữ nghĩa", "sta(else) không nằm cuối", "Nhánh sta(else) bắt buộc phải nằm ở vị trí cuối cùng trong khối bolean.", "4"),
    ErrorCatalogEntry("E401", "Pha 4: Chạy", "Chia cho 0 hoặc lỗi toán học", "Phép chia / hoặc % cho 0, hoặc math.sqrt của số âm.", "9, 15"),
    ErrorCatalogEntry("E402", "Pha 4: Chạy", "Tràn ngăn xếp / quá 65.536 Reback", "Đệ quy vượt quá 800 tầng hoặc khối ids sb thực hiện Reback $$ quá 65.536 lần.", "13, G"),
    ErrorCatalogEntry("E403", "Pha 4: Chạy", "Lỗi nhập liệu hoặc đọc tệp", "Nhập sai kiểu cho ipt, hết dữ liệu nhập, hoặc file.read gặp tệp không tồn tại.", "3, 15, 19"),
    ErrorCatalogEntry("E404", "Pha 4: Chạy", "Chỉ số mảng ngoài khoảng", "Truy cập phần tử mảng ngoài phạm vi 0 .. N-1.", "6"),
    ErrorCatalogEntry("E405", "Pha 4: Chạy", "Con trỏ rỗng hoặc tràn số nguyên", "Giải tham chiếu con trỏ null/đã free hoặc kết quả int vượt quá 2^53.", "12, 19"),
    ErrorCatalogEntry("E406", "Pha 4: Chạy", "sta.free sai", "Giải phóng con trỏ rỗng hoặc giải phóng hai lần (double free).", "12"),
    ErrorCatalogEntry("E407", "Pha 4: Chạy", "Hết bộ nhớ Heap", "Cấp phát vượt giới hạn 2.000.000 ô nhớ của Heap mô phỏng.", "12, G"),
    ErrorCatalogEntry("E408", "Pha 4: Chạy", "Đọc/ghi ngoài vùng Heap", "Truy cập con trỏ lệch ngoài số ô đã cấp phát bởi sta.alloc.", "12"),
    ErrorCatalogEntry("E409", "Pha 4: Chạy", "$$ ngoài ids sb (Hard Halt)", "Cờ $$ xuất hiện ngoài khối ids sb gây dừng khẩn cấp toàn hệ thống.", "10, 13"),
    ErrorCatalogEntry("E501", "Hệ thống", "Vượt giới hạn tài nguyên", "Vượt quá 4.000.000 bước thực thi hoặc 400.000 ký tự đầu ra.", "19, G"),
    ErrorCatalogEntry("W201", "Cảnh báo", "Vòng while không đổi điều kiện", "Thân vòng while không chỉnh sửa biến nào có mặt trong điều kiện.", "5"),
    ErrorCatalogEntry("W202", "Cảnh báo", "Dùng cú pháp cũ", "Dùng /dten không ngoặc nhọn, break $$, transf $ $, hoặc mảng toàn cục ngăn bởi dấu phẩy.", "3, 11, 13"),
    ErrorCatalogEntry("W203", "Cảnh báo", "Nhánh Reback không sửa biến sb", "Nhánh kết thúc bằng $$ không thấy chỉnh trực tiếp biến của khối ids sb.", "13"),
    ErrorCatalogEntry("W204", "Cảnh báo", "Địa chỉ cố định mô phỏng", "ref* ten : 0x... : $ chỉ mô phỏng dưới dạng số nguyên địa chỉ.", "14"),
    ErrorCatalogEntry("W205", "Cảnh báo", "Thanh ghi mô phỏng", "0Ax–0Dx là bốn thanh ghi mô phỏng trong bộ nhớ máy ảo, không chạm CPU thật.", "21"),
    ErrorCatalogEntry("W206", "Cảnh báo", "Vùng *m thừa trong AaC", "Vùng *m ... :& nằm trong phạm vi impt*AaC là thừa.", "21"),
    ErrorCatalogEntry("W207", "Cảnh báo", "Ghi chú */ phụ thuộc dòng", "Ghi chú */ chỉ còn để đọc mã cũ; nên dùng \\\\ ... \\\\ hoặc \\\\* ... *\\\\.", "22"),
    ErrorCatalogEntry("W208", "Cảnh báo", "Ngăn dưới stop không chạy", "Ngăn nằm sau một ngăn có công tắc stop bị bỏ qua hoàn toàn.", "23"),
    ErrorCatalogEntry("W301", "Cảnh báo", "Biến hoặc hàm không dùng", "Khai báo nhưng chưa từng tham chiếu (biến bắt đầu bằng _ được miễn).", "19"),
    ErrorCatalogEntry("W302", "Cảnh báo", "Rò rỉ bộ nhớ Heap", "Có sta.alloc nhưng không có sta.free tương ứng hoặc còn ô Heap chưa giải phóng khi kết thúc.", "12, 19"),
  )
}
