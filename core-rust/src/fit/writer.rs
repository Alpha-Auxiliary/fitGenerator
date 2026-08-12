#[cfg(test)]
mod tests {
    use crate::{
        error::CoreError,
        fit::writer::{FieldDef, FitDataWriter},
    };

    #[test]
    fn writes_a_little_endian_definition() {
        let mut writer = FitDataWriter::new();
        writer
            .write_definition(
                0,
                20,
                &[FieldDef {
                    number: 253,
                    size: 4,
                    base_type: 0x86,
                }],
            )
            .unwrap();

        assert_eq!(writer.as_slice(), &[0x40, 0, 0, 20, 0, 1, 253, 4, 0x86]);
    }

    #[test]
    fn writes_a_data_header_and_little_endian_u32() {
        let mut writer = FitDataWriter::new();
        writer.write_data_header(0).unwrap();
        writer.push_u32_le(0x1234_5678).unwrap();

        assert_eq!(writer.as_slice(), &[0, 0x78, 0x56, 0x34, 0x12]);
    }

    #[test]
    fn rejects_an_invalid_local_message_number() {
        let mut writer = FitDataWriter::new();

        assert!(matches!(
            writer.write_data_header(16),
            Err(CoreError::FitEncodingFailed(message))
                if message == "FIT 本地消息编号必须在 0 到 15 之间"
        ));
    }

    #[test]
    fn rejects_a_zero_sized_field() {
        let mut writer = FitDataWriter::new();

        assert!(matches!(
            writer.write_definition(
                0,
                20,
                &[FieldDef {
                    number: 253,
                    size: 0,
                    base_type: 0x86,
                }],
            ),
            Err(CoreError::FitEncodingFailed(message)) if message == "FIT 字段长度必须大于 0"
        ));
    }

    #[test]
    fn rejects_more_than_255_fields() {
        let mut writer = FitDataWriter::new();
        let fields = vec![
            FieldDef {
                number: 1,
                size: 1,
                base_type: 0x02,
            };
            256
        ];

        assert!(matches!(
            writer.write_definition(0, 20, &fields),
            Err(CoreError::FitEncodingFailed(message))
                if message == "FIT 定义消息的字段数量不能超过 255"
        ));
    }
}
use crate::error::CoreError;

/// One field entry in a FIT definition message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FieldDef {
    pub number: u8,
    pub size: u8,
    pub base_type: u8,
}

/// A bounded, fallible byte writer for FIT data and definition records.
#[derive(Debug, Default)]
pub struct FitDataWriter {
    data: Vec<u8>,
}

impl FitDataWriter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_capacity(capacity: usize) -> Result<Self, CoreError> {
        let mut data = Vec::new();
        data.try_reserve(capacity).map_err(|_| memory_limit_error())?;
        Ok(Self { data })
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.data
    }

    pub fn into_inner(self) -> Vec<u8> {
        self.data
    }

    pub fn write_definition(
        &mut self,
        local: u8,
        global: u16,
        fields: &[FieldDef],
    ) -> Result<(), CoreError> {
        validate_local_message_number(local)?;
        if fields.len() > u8::MAX as usize {
            return Err(CoreError::FitEncodingFailed(
                "FIT 定义消息的字段数量不能超过 255".to_owned(),
            ));
        }
        if fields.iter().any(|field| field.size == 0) {
            return Err(CoreError::FitEncodingFailed(
                "FIT 字段长度必须大于 0".to_owned(),
            ));
        }

        self.reserve(6 + fields.len() * 3)?;
        self.data.extend_from_slice(&[
            0x40 | local,
            0,
            0,
            global as u8,
            (global >> 8) as u8,
            fields.len() as u8,
        ]);
        for field in fields {
            self.data
                .extend_from_slice(&[field.number, field.size, field.base_type]);
        }
        Ok(())
    }

    pub fn write_data_header(&mut self, local: u8) -> Result<(), CoreError> {
        validate_local_message_number(local)?;
        self.push_u8(local)
    }

    pub fn append_bytes(&mut self, bytes: &[u8]) -> Result<(), CoreError> {
        self.reserve(bytes.len())?;
        self.data.extend_from_slice(bytes);
        Ok(())
    }

    pub fn push_u8(&mut self, value: u8) -> Result<(), CoreError> {
        self.reserve(1)?;
        self.data.push(value);
        Ok(())
    }

    pub fn push_u16_le(&mut self, value: u16) -> Result<(), CoreError> {
        self.append_bytes(&value.to_le_bytes())
    }

    pub fn push_u32_le(&mut self, value: u32) -> Result<(), CoreError> {
        self.append_bytes(&value.to_le_bytes())
    }

    pub fn push_i32_le(&mut self, value: i32) -> Result<(), CoreError> {
        self.append_bytes(&value.to_le_bytes())
    }

    fn reserve(&mut self, additional: usize) -> Result<(), CoreError> {
        self.data
            .try_reserve(additional)
            .map_err(|_| memory_limit_error())
    }
}

fn validate_local_message_number(local: u8) -> Result<(), CoreError> {
    if local > 15 {
        return Err(CoreError::FitEncodingFailed(
            "FIT 本地消息编号必须在 0 到 15 之间".to_owned(),
        ));
    }
    Ok(())
}

fn memory_limit_error() -> CoreError {
    CoreError::ResourceLimit("FIT 编码需要的内存过大".to_owned())
}
