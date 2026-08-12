/// Computes the FIT CRC-16 used for file headers and payloads.
pub fn fit_crc(bytes: &[u8]) -> u16 {
    const TABLE: [u16; 16] = [
        0x0000, 0xcc01, 0xd801, 0x1400, 0xf001, 0x3c00, 0x2800, 0xe401, 0xa001, 0x6c00, 0x7800,
        0xb401, 0x5000, 0x9c01, 0x8801, 0x4400,
    ];

    let mut crc = 0u16;
    for &byte in bytes {
        let low = TABLE[(crc as u8 & 0x0f) as usize];
        crc = (crc >> 4) ^ low ^ TABLE[(byte & 0x0f) as usize];

        let high = TABLE[(crc as u8 & 0x0f) as usize];
        crc = (crc >> 4) ^ high ^ TABLE[(byte >> 4) as usize];
    }

    crc
}

#[cfg(test)]
mod tests {
    use super::fit_crc;

    #[test]
    fn calculates_the_fit_header_crc_vector() {
        let bytes = [0x0e, 0x20, 0x82, 0x08, 0, 0, 0, 0, b'.', b'F', b'I', b'T'];

        assert_eq!(fit_crc(&bytes), 0x675d);
    }
}
