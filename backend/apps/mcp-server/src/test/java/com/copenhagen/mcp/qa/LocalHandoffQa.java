package com.copenhagen.mcp.qa;

/** QA3의 8093 포트를 유지하므로 기존 Secure Tunnel 프로세스를 재사용할 수 있다. */
public final class LocalHandoffQa {
    private LocalHandoffQa() {
    }

    public static void main(String[] args) throws InterruptedException {
        LocalCartQa.run(args, true);
    }
}
