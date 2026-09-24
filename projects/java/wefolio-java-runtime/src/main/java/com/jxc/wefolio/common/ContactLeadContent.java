package com.jxc.wefolio.common;

import com.jxc.wefolio.entity.ContactLeadEntity;

/** 留资五项业务内容的精确比较，不包含来源、访客、同意版本或幂等键。 */
public record ContactLeadContent(String contactName, String phone, String wechat,
                                 String desiredSchedule, String needs) {

    /** 复用输入的首尾空白规范化，兼容历史可选字段的空值。 */
    public ContactLeadContent {
        contactName = normalize(contactName);
        phone = normalize(phone);
        wechat = normalize(wechat);
        desiredSchedule = normalize(desiredSchedule);
        needs = normalize(needs);
    }

    /** 联系方式由调用方按既有存储方式读取或解密。 */
    public boolean matches(ContactLeadEntity lead, String storedPhone, String storedWechat) {
        return equals(new ContactLeadContent(lead.getContactName(), storedPhone, storedWechat,
                lead.getDesiredSchedule(), lead.getNeeds()));
    }

    /** 不改变大小写或正文内部空白。 */
    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
