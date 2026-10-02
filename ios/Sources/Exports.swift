import Foundation

enum Exports {
    static func escape(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;") }
    static func csvCell(_ s: String) -> String {
        let first = s.trimmingCharacters(in: .whitespacesAndNewlines).first
        let safe = first.map { "=+-@".contains($0) } == true ? "'" + s : s
        return "\"" + safe.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
    static func data(_ notes: [Note], format: String, passwords: Bool) -> Data {
        let text: String
        if format == "CSV" {
            let rows = [["标题","用户名","密码","网址","说明"]] + notes.filter { $0.kind == "account" }.map { [$0.displayTitle,$0.username,passwords ? $0.password : "[已隐藏]",$0.url,$0.body] }
            text = "\u{FEFF}" + rows.map { $0.map(csvCell).joined(separator: ",") }.joined(separator: "\r\n")
        } else if format == "HTML" {
            text = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>拾笺导出</title><body>" + notes.map { n in
                let fields = n.kind == "account" ? "<p>用户名：\(escape(n.username))<br>密码：\(escape(passwords ? n.password : "[已隐藏]"))<br>网址：\(escape(n.url))</p>" : ""
                return "<h1>\(escape(n.displayTitle))</h1>" + fields + "<div style=\"white-space:pre-wrap;font-size:\(n.fontSize)px\">\(escape(n.body))</div>" + n.images.map { "<p><img style=\"max-width:100%\" alt=\"笔记图片\" src=\"data:image/jpeg;base64,\($0)\"></p>" }.joined() + "<hr>"
            }.joined() + "</body></html>"
        } else {
            text = notes.map { n in n.displayTitle + "\n" + (n.kind == "account" ? "用户名：\(n.username)\n密码：\(passwords ? n.password : "[已隐藏]")\n网址：\(n.url)\n" : "") + n.body + (n.images.isEmpty ? "" : "\n[图片未包含在 TXT 中]") }.joined(separator: "\n\n────\n\n")
        }
        return Data(text.utf8)
    }
}
