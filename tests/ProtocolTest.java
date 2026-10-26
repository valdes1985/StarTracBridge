package com.startrac.bridge;
public final class ProtocolTest {
    private static int tested;
    private static void test(String hex,double kmh,long dist,long seconds) {
        GymConnectDecoder.Reading d=GymConnectDecoder.decode(GymConnectDecoder.fromHex(hex));
        if (d==null || Math.abs(d.speedKmh-kmh)>0.001 || d.distanceMeters!=dist || d.elapsedSeconds!=seconds)
            throw new AssertionError("Mismatch: " + hex);
        tested++;
    }
    public static void main(String[] args) {
        test("53-CB-0E-48-00-00-00-90-01-3C-00-00-00-00-03-00-CE-00-1D",4,60,72);
        test("53-CB-0E-5F-00-00-00-CC-01-5A-00-00-00-00-04-00-EF-00-20",4.6,90,95);
        test("53-CB-0E-63-00-00-00-E4-02-64-00-00-00-00-05-00-CE-01-2D",7.4,100,99);
        test("53-CB-0E-64-00-00-00-20-03-64-00-00-00-00-05-00-12-02-30",8,100,100);
        test("53-CB-0E-78-00-00-00-E8-03-8C-00-00-00-00-08-00-DE-02-68",10,140,120);
        test("53-CB-0E-85-00-00-00-C0-03-B4-00-00-00-00-0B-00-CD-02-67",9.6,180,133);
        test("53-CB-0E-8D-00-00-00-90-01-C8-00-00-00-00-0C-00-E4-00-1F",4,200,141);
        test("53-CB-0E-C7-00-00-00-72-01-04-01-00-00-00-0F-00-CE-00-1D",3.7,260,199);
        if (GymConnectDecoder.decode(GymConnectDecoder.fromHex("22-90-B0-00-00-00"))!=null)
            throw new AssertionError("Six-byte frame must be ignored");
        byte[] a=RscCodec.encode(8,100), b=RscCodec.encode(10,140);
        if (a.length!=8 || b.length!=8 || (a[0]&0xff)!=6 || (a[3]&0xff)!=0 || (b[3]&0xff)!=0)
            throw new AssertionError("Unexpected RSC structure");
        // 8 km/h = 2.222... m/s, encoded as 569/256 m/s.
        if ((a[1]&255)!=0x39 || (a[2]&255)!=0x02 || (a[4]&255)!=0xE8 || (a[5]&255)!=0x03)
            throw new AssertionError("RSC speed/distance encoding incorrect");
        if ((b[1]&255)!=0xC7 || (b[2]&255)!=0x02)
            throw new AssertionError("RSC speed 10 km/h incorrect");
        System.out.println("PASS: "+tested+" real BLE frames; six-byte filter; RSC units and flags");
    }
}
