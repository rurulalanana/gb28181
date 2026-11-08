package io.github.rurulalanana.dahua;

import com.netsdk.lib.NetSDKLib;
import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.internal.DahuaSdk;
import io.github.rurulalanana.deviceaccess.ConnectMode;

/**
 * Recovery probe (ASCII only on purpose: keep this file free of non-ASCII text
 * so it can never be mangled by encoding round-trips).
 *
 * <p>Problem: {@code VideoInOptions} accumulated 35976 occurrences of
 * {@code params.table} because every "read whole -> write whole" round trip
 * nests the envelope one level deeper (the device merges, it never replaces).
 * Once the payload is big enough, {@code CLIENT_GetNewDevConfig} fails outright.
 *
 * <p>This probe calls {@code CLIENT_DeleteDevConfig} so the device rebuilds the
 * config item from defaults.
 */
public final class ConfigResetProbe {

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "VideoInOptions";
        boolean dryRun = args.length > 1 && "--dry".equals(args[1]);

        DahuaSdk sdk = DahuaSdk.get();
        if (!sdk.init()) {
            System.out.println("SDK init failed");
            return;
        }
        DahuaSdk.Session session = sdk.session("192.168.10.156", 37777, "admin", "gxhy@123",
                ConnectMode.CLIENT, "10001");
        if (session == null) {
            System.out.println("login failed");
            return;
        }
        System.out.println("logged in, target=" + cmd + " dryRun=" + dryRun);

        DahuaExtAccess ext = new DahuaExtAccess();
        try {
            String raw = ext.readConfig(session, cmd, 0);
            String biz = DahuaExtAccess.businessLayer(raw);
            System.out.println("before: business=" + biz.length() + " chars, params="
                    + count(biz, "params"));
        } catch (Throwable t) {
            System.out.println("before: read FAILED -> " + t.getMessage());
        }
        if (dryRun) {
            System.out.println("dry run, nothing deleted");
            System.exit(0);
        }

        NetSDKLib.NET_IN_DELETECFG in = new NetSDKLib.NET_IN_DELETECFG();
        NetSDKLib.NET_OUT_DELETECFG out = new NetSDKLib.NET_OUT_DELETECFG();
        in.szCommand = cmd;
        in.write();

        boolean ok = sdk.sdk().CLIENT_DeleteDevConfig(session.handle(), in, out, 8000);
        int last = sdk.sdk().CLIENT_GetLastError();
        System.out.println("DeleteDevConfig ok=" + ok + " nError=" + out.nError
                + " lastError=" + last + " (0x" + Integer.toHexString(last) + ")");

        Thread.sleep(2000);
        try {
            String raw = ext.readConfig(session, cmd, 0);
            String biz = DahuaExtAccess.businessLayer(raw);
            System.out.println("after: business=" + biz.length() + " chars, params="
                    + count(biz, "params"));
            System.out.println("sample: " + biz.substring(0, Math.min(220, biz.length())));
        } catch (Throwable t) {
            System.out.println("after: read still FAILED -> " + t.getMessage());
        }
        System.exit(0);
    }

    private static int count(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
