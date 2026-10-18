using GooglyProtocol;

namespace GooglyWindows;

/// <summary>
/// Advertises the PC on the local network as <c>&lt;hostname&gt;._googly._tcp</c> —
/// what NWListener's Bonjour service registration did on the Mac. Rebuilt whenever the
/// listener restarts on a new port.
/// </summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private readonly Makaretu.Dns.ServiceDiscovery _discovery;

    public MdnsAdvertiser(string instanceName, int port)
    {
        _discovery = new Makaretu.Dns.ServiceDiscovery();
        _discovery.Advertise(new Makaretu.Dns.ServiceProfile(instanceName, GooglyService.Type, (ushort)port));
    }

    public void Dispose() => _discovery.Dispose();
}
