namespace GooglyProtocol;

/// <summary>
/// The mDNS presence the PC advertises (v0.3.4): the service plus a TXT marker that
/// says which app we are. Another Bluey fork shares this Wi-Fi and the same service
/// NAME — both apps advertise the hostname under <c>_googly._tcp</c> (2026-10-24:
/// ours on 51826, theirs on 47821), and a phone-side mDNS resolver can't tell two
/// same-named services apart, so it hands them out like a coin flip. The marker lets
/// a phone that knows it pick us at discovery time; resolvers that don't simply
/// ignore an unknown TXT key (no breakage — TXT records are optional by design).
/// The hard guarantee is the greeting check: our PC answers a new connection with a
/// packet whose <c>hello</c> field is set; the other fork answers with a
/// <c>command</c>. Lives here, next to <see cref="GooglyService"/>, because the
/// marker is part of what the phone and the PC agree on.
/// </summary>
public static class AdvertisedService
{
    public const string MarkerKey = "go";
    public const string MarkerValue = "pc";

    /// <summary>The advertised profile: the service plus our TXT marker.</summary>
    public static Makaretu.Dns.ServiceProfile CreateProfile(string instanceName, int port)
    {
        var profile = new Makaretu.Dns.ServiceProfile(instanceName, GooglyService.Type, (ushort)port);
        profile.AddProperty(MarkerKey, MarkerValue);
        return profile;
    }
}
