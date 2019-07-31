/**
 * 此Controller模拟了一个购买商品后商品减少的场景，通过Redis实现分布式锁，
 * 解决了在分布式应用中的高并发场景下出现超卖，重复卖的问题，此思路可以应用在多种场景
 * 实际开发中可以使用Redisson实现Redis分布式锁，以下思路没有解决Redis主从复制，主机宕机可能造成
 * LockKey丢失的问题，Redis官方提供了RedLock来解决这个问题，具体自行了解
 * 
 * @author Mr.Don
 *
 */
@RestController
public class blockTestController {

	//通过页面返回的端口号来区分当前Nginx选择的服务器
	@Value("${server.port}")
	private String port;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@RequestMapping("/test")
	public String test() {
			String uuid = UUID.randomUUID().toString();
			String lockKey = "lockKey";
			try {
			//通过循环将没有获得到锁的请求一直尝试获取锁
			while (true) {
				/*
				 * 使用setnx设置LockKey，当LockKey设置成功时，返回true，
				 * 当有其他请求已经设置了LockKey则返回false，必须等已经获取锁(设置了LockKey)的
				 * 请求释放锁后(删除设置的LockKey)再获取锁
				 */
				boolean result = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, uuid, 10, TimeUnit.SECONDS);
				if (result) {
					/*
					 * 次线程是为了解决LockKey设置过期时间后，如果请求在LockKey过期
					 * 后还没执行完整个程序，则会导致当前请求还未释放锁，就有其他的请求获取了锁
					 * 最终导致线程不安全，可能会导致锁永久失效，所以在请求获取锁之后，会启动单独一个线程
					 * 这个线程创建一个定时任务，定期给锁进行续期，定时任务执行周期要小于LockKey的过期时间，
					 * 具体时间更具业务需求来设置，Redisson框架也实现了续期的功能
					 */
					new Thread() {
						public void run() {
							new Timer().schedule(new TimerTask() {
								@Override
								public void run() {
									stringRedisTemplate.expire(lockKey,10 , TimeUnit.SECONDS);
									//判断当前请求是否释放锁，如果已释放锁，就将此请求的续期功能关闭
									if (stringRedisTemplate.opsForValue().get(lockKey) != uuid)
										this.cancel();
								}
							}, 5000, 5000);
						}
					}.start();
					break;
				}
			}
			//在Redis服务器中有一个Key为num的数据，用来模拟商品的数量
			String value = stringRedisTemplate.opsForValue().get("num");
			int num = Integer.parseInt(value);
			if (num <= 0) {
				System.out.println("库存不足");
			} else {
				System.out.println("库存数量: "+num--);
				stringRedisTemplate.opsForValue().set("num", String.valueOf(num));
			}
		} finally {
			stringRedisTemplate.delete(lockKey);
		}
		return "port: " + port;

	}
}
