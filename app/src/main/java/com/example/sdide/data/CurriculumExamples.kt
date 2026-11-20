package com.example.sdide.data

data class SampleProject(
  val id: String,
  val title: String,
  val chapter: String,
  val category: String,
  val description: String,
  val entryFile: String,
  val files: LinkedHashMap<String, String>,
  val defaultStdin: String = "",
)

data class SyntaxCheatsheetItem(
  val category: String,
  val syntax: String,
  val meaning: String,
)

object CurriculumExamples {
  val samples: List<SampleProject> = listOf(
    SampleProject(
      id = "ch1_hello",
      title = "Xin chào & Khung chuẩn",
      chapter = "Chương 1–2",
      category = "Sd 2 Cơ bản",
      description = "Chương trình Sd 2 chuẩn với imp*ioprt.ed, biến int/str và pri.",
      entryFile = "xin_chao.sd",
      files = linkedMapOf(
        "xin_chao.sd" to """
imp*ioprt.ed
sta(main){
    \\ Chuong trinh dau tien bang ngon ngu Sd \\
    iid: sta(str ten) => edt: sta(ten = "Hoc vien Sd") ed
    iid: sta(int nam) => edt: sta(nam = 2026) ed
    iid: sta(pri) => "Xin chao /s{ten}! Chao mung nam /d{nam}." ed
    iid: ref* 0 ed
}
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch5_loops",
      title = "Bolean, For & Nhảy brk/nxt",
      chapter = "Chương 4–5",
      category = "Sd 2 Cơ bản",
      description = "Rẽ nhánh sta(else) và vòng lặp for 3 mục ngăn bởi dấu nháy đơn '.",
      entryFile = "vong_lap.sd",
      files = linkedMapOf(
        "vong_lap.sd" to """
imp*ioprt.ed
sta(main){
    iid: sta(int tong) => edt: sta(tong = 0) ed
    iid: sta(for) => cdn,chck:
    dbl sta(int i = 1 ' i <= 10 ' i++){
        iid: sta(bolean) => cdn,tf:
        sta(i % 2 != 0){
            iid: nxt ed
        }
        bln ed
        iid: sta(tong = tong + i) ed
        iid: sta(pri) => "Cong so chan /d{i} -> tong = /d{tong}" ed
    }
    dbl ed
    iid: sta(pri) => "Tong cuoi = /d{tong}" ed
    iid: ref* 0 ed
}
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch8_struct_fn",
      title = "Struct, Mảng & Hàm",
      chapter = "Chương 6–8",
      category = "Sd 2 Cơ bản",
      description = "Quản lý điểm sinh viên dùng struct, hàm đệ quy và truyền mảng tham chiếu.",
      entryFile = "quan_ly_diem.sd",
      files = linkedMapOf(
        "quan_ly_diem.sd" to """
imp*ioprt.ed
imp*math.ed

iid: sta(struct) => dta:
sta SinhVien{
    str ten '
    float d_toan '
    float d_ly '
    float d_hoa
}
strt ed

iid: sta(fn) => rtn:
sta tinh_tb(SinhVien sv) -> float{
    iid: sta(float tb) => edt: sta(tb = (sv.d_toan + sv.d_ly + sv.d_hoa) / 3.0) ed
    iid: ref* tb ed
}
fn ed

iid: sta(void) => unkw:
sta nhan_doi(int ds[3]){
    iid: sta(for) => cdn,chck:
    dbl sta(int i = 0 ' i < 3 ' i++){
        iid: sta(ds[i] = ds[i] * 2) ed
    }
    dbl ed
}
void ed

sta(main){
    iid: sta(SinhVien sv) ed
    iid: sta(sv.ten = "Nguyen Van A") ed
    iid: sta(sv.d_toan = 8.5) ed
    iid: sta(sv.d_ly = 9.0) ed
    iid: sta(sv.d_hoa = 7.75) ed
    iid: sta(float tb) => edt: sta(tb = tinh_tb(sv)) ed
    iid: sta(pri) => "Sinh vien: /s{sv.ten} | TB = /f3{tb}" ed

    iid: sta(int mang[3]) => edt: sta(mang = [5 ' 10 ' 15]) ed
    iid: call nhan_doi(mang) ed
    iid: sta(pri) => "Sau nhan doi: /d{mang[0]} ' /d{mang[1]} ' /d{mang[2]}" ed
    iid: ref* 0 ed
}
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch11_sdpp_flat",
      title = "Sd++ Trần, define & Toàn cục",
      chapter = "Chương 10–11",
      category = "Sd++ Hiện đại",
      description = "Khung ssta { ... } ##, cờ $, ₫, khối define[...] { ... } € và biến toàn cục.",
      entryFile = "sdpp_co_ban.sdpp",
      files = linkedMapOf(
        "sdpp_co_ban.sdpp" to """
ssta {
    imp*ioprt.$
    ids.g: dem = 10 ₫
    sta.arr.g: he_so = [2 ' 4 ' 6] ₫

    define[
        ids.edt: text = "chinh",
        pri.edt: text = "in_ra"
    ]{
        chinh: {
            iptedt.int tong = ids(dem).g + he_so[1] $
            in_ra: "Gia tri dem toan cuc = /d{dem}" $
            in_ra: "Tong tinh duoc = /d{tong}" $
            ref*0 $:
        } $
    } €
} ##
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch12_heap_ptr",
      title = "Con trỏ & Bộ nhớ Heap (8 byte)",
      chapter = "Chương 12",
      category = "Sd++ Hiện đại",
      description = "Cấp phát động sta.alloc, giải tham chiếu con trỏ ptr<int> và thu hồi sta.free.",
      entryFile = "heap_demo.sdpp",
      files = linkedMapOf(
        "heap_demo.sdpp" to """
ssta {
    imp*ioprt.$
    imp*mem.$

    iptedt.int x = 50 $
    iptedt.ptr<int> px = @x $
    *px = *px + 25 $
    ids.pri: "Sau tang qua con tro px: x = /d{x}" $

    iptedt.ptr<int> heap_arr = sta.alloc(4) $
    *(heap_arr + 0) = 100 $
    *(heap_arr + 1) = 200 $
    heap_arr[2] = 300 $
    heap_arr[3] = 400 $
    ids.pri: "O Heap [1] = /d{*(heap_arr + 1)}, [3] = /d{heap_arr[3]}" $

    sta.free(heap_arr) $
    ref*0 $:
} ##
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch13_sb_reback",
      title = "Khối chọn lọc ids sb & Reback $$",
      chapter = "Chương 13",
      category = "Sd++ Hiện đại",
      description = "Điều phối luồng với transf $, break $ và quay lại đầu khối bằng Reback $$.",
      entryFile = "reback_demo.sdpp",
      files = linkedMapOf(
        "reback_demo.sdpp" to """
ssta {
    imp*ioprt.$
    iptedt.int buoc = 1 $

    ids sb(buoc) {
        block buoc == 1:
            ids.pri: "Pha 1: Khoi tao he thong" $
            buoc = 2 $
            $$
        block buoc == 2:
            ids.pri: "Pha 2: Kiem tra dieu kien (transf)" $
            buoc = 3 $
            transf $
        block buoc == 3:
            ids.pri: "Pha 3: Hoan tat quy trinh!" $
            break $
        remaining:
            ids.pri: "Trang thai khac" $
            break $
    } $

    ids.pri: "Da thoat khoi ids sb." $
    ref*0 $:
} ##
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch14_mirror_defk",
      title = "Mirror (*m), :& và cờ defk ¥",
      chapter = "Chương 14",
      category = "Sd++ Hiện đại",
      description = "Tự suy luận kiểu trong vùng *m ... :&, kết hợp cờ tự định nghĩa defk ¥.",
      entryFile = "mirror_defk.sdpp",
      files = linkedMapOf(
        "mirror_defk.sdpp" to """
ssta {
    imp*ioprt.$
    defk<ids.end: text "¥">{ # }
    init ¥

    init.endcomand: ":&" $

    *m
        ten = "Sd++ 3.0"
        phien_ban = 3
        diem = 9.85
        ids.pri: "Ngon ngu /s{ten} v/d{phien_ban} - Diem /f{diem}"
    :&

    ids.pri: "Dong lenh voi co tu tao ¥" ¥
    ref*0 ¥:
} ##
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch15_multifile",
      title = "Dự án Đa tệp & Thư viện chuẩn",
      chapter = "Chương 15",
      category = "Đa tệp",
      description = "Tạo thư viện riêng tien_ich.sd, nạp bằng imp*tien_ich.ed và dùng math/str.",
      entryFile = "bai.sd",
      files = linkedMapOf(
        "bai.sd" to """
imp*ioprt.ed
imp*math.ed
imp*str.ed
imp*tien_ich.ed

sta(main){
    iid: sta(int kq) => edt: sta(kq = tien_ich.gap_doi(21)) ed
    iid: sta(float can) => edt: sta(can = math.sqrt(64)) ed
    iid: sta(str loi_chao) => edt: sta(loi_chao = str.upper("xin chao da tep")) ed

    iid: sta(pri) => "gap_doi(21) = /d{kq} | sqrt(64) = /f{can}" ed
    iid: sta(pri) => "Chu hoa: /s{loi_chao}" ed
    iid: ref* 0 ed
}
        """.trimIndent(),
        "tien_ich.sd" to """
\\ Thu vien nguoi dung tien_ich.sd \\
iid: sta(fn) => rtn:
sta gap_doi(int x) -> int{
    iid: ref* x * 2 ed
}
fn ed
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch21_registers_aac",
      title = "Thanh ghi 0Ax–0Dx & AaC (.sdpp)",
      chapter = "Chương 21",
      category = "Phần III: Hệ thống",
      description = "Nạp thanh ghi #sdbin:, triệt tiêu nx{ } và vùng mượn tự động impt*AaC.",
      entryFile = "thanh_ghi.sdpp",
      files = linkedMapOf(
        "thanh_ghi.sdpp" to """
ssta {
    imp*ioprt.$

    \\ Nap du lieu vao thanh ghi mo phong 0Ax va 0Bx \\
    #{ #sdbin: 0Ax[42] , 0Bx[58] # }#
    ids.pri: "Doc 0Ax = /d{0Ax}, 0Bx = /d{0Bx}" $

    \\ Day ra bien va triet tieu ngay \\
    :p > sdppbin: int a = nx{0Ax} $
    :p > sdppbin: int b = nx{0Bx} $
    ids.pri: "Tong tu thanh ghi = /d{a + b}" $

    \\ Vung AaC tu dong cap phat va thu hoi thanh ghi cho bien muon \\
    impt*AaC.bin.ed
    x:& = 128
    y:& = 256
    ids.pri: "Gia tri muon AaC: *m(x)=/d{*m(x)}, *m(y)=/d{*m(y)}"
    ref*0 $:
} ##
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch23_sdall",
      title = "Dây chuyền Đa ngăn (.sdall)",
      chapter = "Chương 22–23",
      category = "Phần III: Hệ thống",
      description = "Kết hợp ngăn Sd lõi, Sd++ và công tắc start/on/pause/off + //e.",
      entryFile = "day_chuyen.sdall",
      files = linkedMapOf(
        "day_chuyen.sdall" to """
start imp*ioprt.ed
sta(main){
    \\ Chu y: trong .sdall ngan cach cac ngan bang ; o tang ngoai \\
    iid: sta(pri) => "Ngan 1 (Sd loi): Khoi dong he thong//eXuong dong bang //e!" ed
    iid: ref* 0 ed
}
;
on ssta {
    imp*ioprt.$
    #{ #sdbin: 0Ax[999] # }#
    ids.pri: "Ngan 2 (Sd++): Thanh ghi 0Ax = /d{0Ax}" $
    :p > sdppbin: int luu = nx{0Ax} $
    ids.pri: "Ngan 2: Da day va xoa sach 0Ax -> luu = /d{luu}" $
} ##
;
pause ssta {
    imp*ioprt.$
    ids.pri: "Ngan 3 dang PAUSE nen khong chay" $
} ##
;
on ssta {
    imp*ioprt.$
    ids.pri: "Ngan 4: Hoan thanh day chuyen .sdall!" $
} ##
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch9_bughunt",
      title = "Thực hành Săn lỗi (Ví dụ 9.2)",
      chapter = "Chương 9",
      category = "Thực hành Debug",
      description = "Chương trình cố ý chứa lỗi E205/E206/E203 để thử bảng Vấn đề.",
      entryFile = "san_loi.sd",
      files = linkedMapOf(
        "san_loi.sd" to """
\\ Hay bam Chay de xem chan doan loi @dong cot, sau do sua lai! \\
sta(main){
    iid: sta(int tong) => edt: sta(tong = 0) ed
    iid: sta(for) => cdn,chck:
    dbl sta(int i = 1 ' i <= 5){
        iid: sta(tong = tong + i) ed
    }
    dbl ed
    iid: sta(pri) => "Tong = /d{tong}" ed
    iid: ref* 0 ed
}
        """.trimIndent(),
      ),
    ),
    SampleProject(
      id = "ch10_bubble_sort",
      title = "Sắp xếp nổi bọt",
      chapter = "Chương 10",
      category = "Thuật toán",
      description = "Sắp xếp mảng số nguyên tăng dần bằng thuật toán Bubble Sort.",
      entryFile = "sap_xep_noi_bot.sd",
      files = linkedMapOf(
        "sap_xep_noi_bot.sd" to """
imp*ioprt.ed

sta(main){
    iid: sta(int mang[7]) => edt: sta(mang = [64 ' 34 ' 25 ' 12 ' 22 ' 11 ' 90]) ed
    iid: sta(int n) => edt: sta(n = 7) ed
    iid: sta(int i) ed
    iid: sta(int j) ed
    iid: sta(int tam) ed

    iid: sta(for) => cdn,chck:
    dbl sta(int i = 0 ' i < n - 1 ' i++){
        iid: sta(for) => cdn,chck:
        dbl sta(int j = 0 ' j < n - i - 1 ' j++){
            iid: sta(bolean) => cdn,tf:
            sta(mang[j] > mang[j + 1]){
                iid: sta(tam = mang[j]) ed
                iid: sta(mang[j] = mang[j + 1]) ed
                iid: sta(mang[j + 1] = tam) ed
            }
            bln ed
        }
        dbl ed
    }
    dbl ed

    iid: sta(pri) => "Mang sau sap xep:" ed
    iid: sta(for) => cdn,chck:
    dbl sta(int i = 0 ' i < n ' i++){
        iid: sta(pri) => "/d{mang[i]} " ed
    }
    dbl ed
    iid: ref* 0 ed
}
        """.trimIndent(),
      ),
    ),
  )

  val cheatsheet: List<SyntaxCheatsheetItem> = listOf(
    SyntaxCheatsheetItem("Sd 2 Lõi", "imp*ioprt.ed", "Nạp thư viện I/O chuẩn (pri, ipt)"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "sta(main){ ... iid: ref* 0 ed }", "Khung chính chương trình Sd 2"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "iid: sta(int x) => edt: sta(x = 5) ed", "Khai báo và gán biến kiểu int"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "iid: sta(pri) => \"KQ = /d{x}\" ed", "In kết quả (/d, /f, /f3, /s, /b)"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "iid: sta(ipt) => edt: sta(x) ed", "Đọc dữ liệu từ Stdin vào biến"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "iid: sta(bolean) => cdn,tf: sta(...){ } bln ed", "Rẽ nhánh điều kiện (nhánh cuối sta(else))"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "iid: sta(for) => cdn,chck: dbl sta(int i=0 ' i<5 ' i++){ } dbl ed", "Vòng lặp for (3 mục ngăn bằng dấu ')"),
    SyntaxCheatsheetItem("Sd 2 Lõi", "\\\\ ghi chu \\\\  hoặc  \\\\* khoi *\\\\", "Ghi chú 1 dòng hoặc nhiều dòng lồng nhau"),
    SyntaxCheatsheetItem("Sd++", "ssta { ... } ##", "Vỏ bọc chương trình Sd++ (nắp mở/đóng)"),
    SyntaxCheatsheetItem("Sd++", "iptedt.int x = 10 $", "Khai báo phẳng Sd++ chốt bằng cờ $"),
    SyntaxCheatsheetItem("Sd++", "ids.g: x = 10 ₫", "Biến toàn cục trần chốt bằng cờ ₫"),
    SyntaxCheatsheetItem("Sd++", "ids.pri: \"...\" $", "Lệnh in phẳng Sd++"),
    SyntaxCheatsheetItem("Sd++", "@x  /  *p  /  ptr<int>", "Lấy địa chỉ, giải tham chiếu, con trỏ có kiểu"),
    SyntaxCheatsheetItem("Sd++", "sta.alloc(n)  /  sta.free(p)", "Cấp phát n ô Heap 8-byte & giải phóng (cần imp*mem.$)"),
    SyntaxCheatsheetItem("Sd++", "ids sb(v){ block đk: ... $$ }", "Khối chọn lọc với transf $, break $ và Reback $$"),
    SyntaxCheatsheetItem("Sd++ 3.0", "defk<ids.end: text \"¥\">{ # } / init ¥", "Định nghĩa và kích hoạt cờ chốt tùy biến"),
    SyntaxCheatsheetItem("Sd++ 3.0", "*m ... :&  /  init.endcomand: \":&\" $", "Vùng Mirror tự suy kiểu / vùng lược cờ"),
    SyntaxCheatsheetItem("Hệ thống", "#{ #sdbin: 0Ax[val] # }#", "Nạp giá trị vào thanh ghi mô phỏng 0Ax–0Dx"),
    SyntaxCheatsheetItem("Hệ thống", ":p > sdppbin: int n = nx{0Ax} $", "Đẩy giá trị thanh ghi ra biến và triệt tiêu thanh ghi"),
    SyntaxCheatsheetItem("Hệ thống", "impt*AaC.bin.ed  /  x:&  /  *m(x)", "Quản lý thanh ghi tự động AaC & tham chiếu mượn"),
    SyntaxCheatsheetItem("Đa ngăn", "start ... ; on ... ; pause ... ; stop ...", "Liên kết nhiều ngăn Sd/Sd++ bằng ; trong tệp .sdall"),
  )
}
