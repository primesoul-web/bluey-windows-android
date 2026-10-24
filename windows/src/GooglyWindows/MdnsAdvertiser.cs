using GooglyProtocol;

namespace GooglyWindows;

/// <summary>
/// Advertises the PC on the local network as <c>&lt;hostname&gt;._googly._tcp</c> —
/// what NWListener's Bonjour service registration did on the Mac. Rebuilt whenever the
/// listener restarts on a new port. The profile itself (service name, port, TXT
/// marker — v0.3.4's tell-apart-from-the-other-fork device) lives in
/// <see cref="AdvertisedService"/> next to the rest of the wire protocol.
/// </summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private readonly Makaretu.Dns.ServiceDiscovery _discovery;

    public MdnsAdvertiser(string instanceName, int port)
    {
        _discovery = new Makaretu.Dns.ServiceDiscovery();
        _discovery.Advertise(AdvertisedService.CreateProfile(instanceName, port));
    }

    public void Dispose() => _discovery.Dispose();
}
