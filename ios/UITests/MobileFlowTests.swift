import XCTest

final class MobileFlowTests: XCTestCase {
    func testAutomaticEntryReturnSaveAndAbout() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.buttons["新建"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["新建"].tap(); app.buttons["文本笔记"].tap()
        let body = app.textViews["noteBody"]; XCTAssertTrue(body.waitForExistence(timeout: 10))
        body.tap(); body.typeText("UI first line\nSaved on return")
        app.buttons["保存并返回"].tap()
        XCTAssertTrue(app.staticTexts["UI first line"].waitForExistence(timeout: 30))
        app.terminate(); app.launch()
        XCTAssertTrue(app.staticTexts["UI first line"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.secureTextFields["主密码"].exists)
        app.tabBars.buttons["设置"].tap(); app.buttons["关于"].tap()
        XCTAssertTrue(app.staticTexts["作者 andy"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["andyzf5520/suixinnotes"].exists)
    }
}
