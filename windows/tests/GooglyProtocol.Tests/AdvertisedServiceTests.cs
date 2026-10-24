using GooglyProtocol;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The mDNS TXT marker (v0.3.4): another Bluey fork advertises under the same
/// _googly._tcp name on the user's Wi-Fi, so our service profile carries a TXT
/// property that says which app we are. Phones can use it to skip impostors at
/// discovery time; resolvers that don't know it must ignore it harmlessly.
/// </summary>
public class AdvertisedServiceTests
{
    [Fact]
    public void Profile_carries_the_go_pc_marker()
    {
        var profile = AdvertisedService.CreateProfile("DESKTOP-TEST", 51826);
        var txt = profile.Resources.OfType<Makaretu.Dns.TXTRecord>().SingleOrDefault();
        Assert.NotNull(txt);
        Assert.Contains($"{AdvertisedService.MarkerKey}={AdvertisedService.MarkerValue}", txt.Strings);
    }

    [Fact]
    public void Profile_names_the_service_and_port_unchanged()
    {
        var profile = AdvertisedService.CreateProfile("DESKTOP-TEST", 51826);
        Assert.Equal("DESKTOP-TEST", profile.InstanceName);
        Assert.Equal("_googly._tcp", profile.ServiceName);
        var srv = profile.Resources.OfType<Makaretu.Dns.SRVRecord>().Single();
        Assert.Equal(51826, srv.Port);
    }
}
