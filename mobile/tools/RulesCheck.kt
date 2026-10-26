import com.copenhagen.wallet.data.*
import java.time.YearMonth

fun main() {
    var checks = 0
    fun expect(name: String, actual: Boolean) { checks++; check(actual) { "Failed: $name" } }
    fun rejectedHost(value: String, debug: Boolean = false) = runCatching { normalizeHost(value, debug) }.isFailure

    expect("six-digit PIN", validPin("012345"))
    for (value in listOf("", "12345", "1234567", "１２３４５６", "abcdef", "123 56")) expect("invalid PIN $value", !validPin(value))
    expect("login ID", validLoginId("copen_user"))
    for (value in listOf("abcd", "a".repeat(21), "Copen", "copen.user", "한글아이디")) expect("invalid login ID", !validLoginId(value))
    expect("email", validEmail("user@example.com"))
    for (value in listOf("a@", "@example.com", "a b@example.com", "a@b", "a".repeat(250) + "@example.com")) expect("invalid email", !validEmail(value))
    expect("valid password", passwordError("K9!rT2@p", "user_name") == null)
    for (value in listOf("short1!", "onlyletters", "abcdefgh1!", "K999@ppz", "zYx34!qr", "With space7!", "비밀번호23!", "a".repeat(21)))
        expect("invalid password", passwordError(value, "user_name") != null)
    expect("password equals ID", passwordError("Copen_Us3r", "copen_us3r") != null)

    expect("strip separators", normalizeCardNumber("4111-1111 1111 1111") == "4111111111111111")
    for (value in listOf("4111111111111111", "5555555555554444", "378282246310005", "4222222222222")) {
        expect("Luhn", validCardNumber(value))
        for (last in '0'..'9') if (last != value.last()) expect("Luhn check digit", !validCardNumber(value.dropLast(1) + last))
    }
    for (value in listOf("123", "411111111111111x", "4111 1111 1111 1111", "４１１１１１１１１１１１１１１１", "0".repeat(20)))
        expect("card format", !validCardNumber(value))
    expect("CVC three", validCvc("123")); expect("CVC four", validCvc("1234"))
    for (value in listOf("12", "12345", "12a", "１２３")) expect("CVC rejected", !validCvc(value))
    val now = YearMonth.of(2026, 9)
    expect("expiry current month", validExpiry(9, 2026, now))
    expect("expiry future", validExpiry(1, 2030, now))
    for ((month, year) in listOf(8 to 2026, 0 to 2030, 13 to 2030, 1 to 26, 1 to 10000)) expect("expiry rejected", !validExpiry(month, year, now))

    expect("HTTPS normalized", normalizeHost(" https://api.example.com ", false) == "https://api.example.com/")
    expect("gateway prefix", normalizeHost("https://api.example.com/gateway/", false) == "https://api.example.com/gateway/")
    expect("debug HTTP", normalizeHost("http://10.0.2.2:8082", true) == "http://10.0.2.2:8082/")
    for (value in listOf("", "api.example.com", "http://api.example.com", "ftp://api.example.com", "https://user:pass@api.example.com", "https://api.example.com?q=1", "https://api.example.com#x", "https://api.example.com:99999"))
        expect("host rejected", rejectedHost(value))
    expect("credentials rejected in debug", rejectedHost("http://user:pass@localhost", true))
    val key = newKey()
    expect("canonical key length", key.length == 36)
    expect("key UUID", validId(key))
    expect("different operation keys", key != newKey())
    expect("path injection ID", !validId("../users/me"))
    expect("seven approval states", approvalStatuses.size == 7)
    expect("approved not paid", statusLabel("APPROVED") != statusLabel("COMPLETED"))
    expect("KRW", money(10000) == "10,000원")
    println("$checks production Rules.kt checks passed.")
}
