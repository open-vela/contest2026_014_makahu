//! The assembled network-conditions report.

use std::net::SocketAddr;

/// A snapshot of how this host is seen from the outside, used to seed the magic
/// socket's candidate paths and to decide whether direct hole punching is even
/// worth attempting.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct NetReport {
    /// Our server-reflexive IPv4 address, if a v4 probe succeeded.
    pub reflexive_v4: Option<SocketAddr>,
    /// Our server-reflexive IPv6 address, if a v6 probe succeeded.
    pub reflexive_v6: Option<SocketAddr>,
    /// `Some(true)` once two servers of the same family observed *different*
    /// reflexive addresses — a symmetric-NAT signal that hole punching will
    /// likely fail and a relay is needed. `None` until enough probes have
    /// reported to tell.
    pub mapping_varies: Option<bool>,
}

impl NetReport {
    /// Folds a reflexive address observed via one server into the report.
    pub fn record(&mut self, reflexive: SocketAddr) {
        match reflexive {
            SocketAddr::V4(_) => {
                Self::record_family(&mut self.reflexive_v4, &mut self.mapping_varies, reflexive);
            }
            SocketAddr::V6(_) => {
                Self::record_family(&mut self.reflexive_v6, &mut self.mapping_varies, reflexive);
            }
        }
    }

    fn record_family(
        slot: &mut Option<SocketAddr>,
        mapping_varies: &mut Option<bool>,
        reflexive: SocketAddr,
    ) {
        if let Some(previous) = *slot {
            *mapping_varies = Some(mapping_varies.unwrap_or(false) || previous != reflexive);
        }
        *slot = Some(reflexive);
    }

    /// Whether any reflexive address was learned at all.
    #[must_use]
    pub fn has_reflexive(&self) -> bool {
        self.reflexive_v4.is_some() || self.reflexive_v6.is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_reflexive_addresses_by_family() {
        let mut report = NetReport::default();
        report.record("203.0.113.7:40000".parse().unwrap());
        report.record("[2001:db8::7]:40000".parse().unwrap());
        assert_eq!(
            report.reflexive_v4,
            Some("203.0.113.7:40000".parse().unwrap())
        );
        assert_eq!(
            report.reflexive_v6,
            Some("[2001:db8::7]:40000".parse().unwrap())
        );
        assert!(report.has_reflexive());
    }

    #[test]
    fn detects_mapping_variation_across_servers() {
        let mut report = NetReport::default();
        report.record("203.0.113.7:40000".parse().unwrap());
        assert_eq!(report.mapping_varies, None, "one observation cannot vary");
        report.record("203.0.113.7:40000".parse().unwrap());
        assert_eq!(
            report.mapping_varies,
            Some(false),
            "agreement is not variation"
        );
        report.record("203.0.113.7:55555".parse().unwrap());
        assert_eq!(
            report.mapping_varies,
            Some(true),
            "a differing port is symmetric NAT"
        );
    }
}
